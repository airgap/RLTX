package rltx.vk;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;
import static rltx.vk.VkUtil.check;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryRequirementsInfo2;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedRequirements;
import org.lwjgl.vulkan.VkMemoryRequirements2;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import rltx.scene.GeometryBuffer;
import rltx.scene.StaticScene;

/**
 * The rasterising backend — "Normal" mode. A second {@link Renderer} beside the ray tracer
 * ({@link RtRenderer}) that fills the same externally-shared output image the GL compositor blits,
 * so the plugin drives it through the identical seam. See {@code docs/normal-mode.md}.
 *
 * <p><b>Increment 2b.</b> Rasterises both the static world and the per-frame dynamic geometry
 * flat-shaded by each face's own colour, with the camera matched to {@code trace.comp}'s pinhole
 * convention, z-buffered through a depth image. The static scene is baked to world space and drawn
 * whole — no level or roof culling yet, so roofs and upper floors show; that culling, plus lighting,
 * textures, translucency, water and the shared post chain, are the increments after this. Photo and
 * readback paths fail loudly rather than return a blank frame.
 *
 * <p>Geometry lives in host-visible storage buffers pulled by the vertex shader through
 * {@code gl_VertexIndex}; simple, not fast. The static buffer is refilled only when the scene
 * changes; the dynamic one every frame. Moving the static set to device-local memory and culling
 * it are the next performance steps. It reuses {@code VkContext} as-is, which still requires
 * ray-query support, so Normal runs on a ray-tracing GPU for now.
 */
@Slf4j
public final class NormalRenderer implements Renderer
{
	private static final int OUTPUT_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
	private static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;
	private static final int MAX_DYNAMIC_FACES = 1 << 19;
	private static final int MAX_STATIC_FACES = 3 << 20;
	private static final int PUSH_BYTES = 80;
	private static final float NEAR = 32f;
	private static final float FAR = 65536f;

	private final VkContext ctx;
	private final VkDevice device;
	private final VkQueue queue;
	private final long commandPool;
	private final VkCommandBuffer cmd;

	private long fence;
	private boolean fencePending;
	private long semaphoreVkDone, semaphoreGlDone;
	private long handleVkDone, handleGlDone;
	private long waitNanos;

	// The externally-shared colour image the compositor imports as a GL texture, its depth buffer,
	// and the framebuffer over them. Recreated on resize.
	private long image, memory, view, allocationSize, handle;
	private long depthImage, depthMemory, depthView;
	private long framebuffer;
	private int outputWidth, outputHeight;

	private long renderPass;
	private long descriptorSetLayout, descriptorPool, dynamicDescriptorSet, staticDescriptorSet;
	private long pipelineLayout, pipeline;

	private VkBuf positions, colors;
	private int dynamicFaceCount;

	private VkBuf staticPositions, staticColors;
	private int staticFaceCount;
	// The scenes the front end has handed us, by id; the static buffer is rebuilt from all of them
	// on any change. The transform places a set's local geometry into the world (null is identity).
	private final Map<Integer, float[]> staticTransforms = new HashMap<>();
	private final Map<Integer, StaticScene> staticScenes = new HashMap<>();

	public NormalRenderer(VkContext ctx)
	{
		this(ctx, ctx.queue, ctx.commandPool);
	}

	public NormalRenderer(VkContext ctx, VkQueue queue, long commandPool)
	{
		this.ctx = ctx;
		this.device = ctx.device;
		this.queue = queue;
		this.commandPool = commandPool;
		this.cmd = ctx.allocateCommandBuffer(commandPool);
		createSyncObjects();
		createGeometryBuffers();
		createDescriptors();
		createRenderPass();
		createPipeline();
	}

	private void createSyncObjects()
	{
		try (MemoryStack stack = stackPush())
		{
			VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
			LongBuffer pFence = stack.mallocLong(1);
			check(vkCreateFence(device, fenceInfo, null, pFence), "vkCreateFence");
			fence = pFence.get(0);

			VkExportSemaphoreCreateInfo export = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default()
				.handleTypes(ExternalHandles.SEMAPHORE_HANDLE_TYPE);
			VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(export.address());
			LongBuffer pSem = stack.mallocLong(1);
			check(vkCreateSemaphore(device, semInfo, null, pSem), "vkCreateSemaphore");
			semaphoreVkDone = pSem.get(0);
			check(vkCreateSemaphore(device, semInfo, null, pSem), "vkCreateSemaphore");
			semaphoreGlDone = pSem.get(0);

			handleVkDone = ExternalHandles.exportSemaphore(device, stack, semaphoreVkDone);
			handleGlDone = ExternalHandles.exportSemaphore(device, stack, semaphoreGlDone);
		}
	}

	private void createGeometryBuffers()
	{
		int host = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
		positions = ctx.createBuffer((long) MAX_DYNAMIC_FACES * GeometryBuffer.FLOATS_PER_FACE * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, host);
		colors = ctx.createBuffer((long) MAX_DYNAMIC_FACES * Integer.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, host);
		staticPositions = ctx.createBuffer((long) MAX_STATIC_FACES * GeometryBuffer.FLOATS_PER_FACE * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, host);
		staticColors = ctx.createBuffer((long) MAX_STATIC_FACES * Integer.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, host);
	}

	private void createDescriptors()
	{
		try (MemoryStack stack = stackPush())
		{
			VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
			binds.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
			binds.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
			VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
			LongBuffer pLayout = stack.mallocLong(1);
			check(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout), "vkCreateDescriptorSetLayout");
			descriptorSetLayout = pLayout.get(0);

			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
			sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4);
			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(2).pPoolSizes(sizes);
			LongBuffer pPool = stack.mallocLong(1);
			check(vkCreateDescriptorPool(device, poolInfo, null, pPool), "vkCreateDescriptorPool");
			descriptorPool = pPool.get(0);

			dynamicDescriptorSet = allocateSet(stack);
			staticDescriptorSet = allocateSet(stack);
			writeSet(stack, dynamicDescriptorSet, positions, colors);
			writeSet(stack, staticDescriptorSet, staticPositions, staticColors);
		}
	}

	private long allocateSet(MemoryStack stack)
	{
		VkDescriptorSetAllocateInfo alloc = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
			.descriptorPool(descriptorPool)
			.pSetLayouts(stack.longs(descriptorSetLayout));
		LongBuffer pSet = stack.mallocLong(1);
		check(vkAllocateDescriptorSets(device, alloc, pSet), "vkAllocateDescriptorSets");
		return pSet.get(0);
	}

	private void writeSet(MemoryStack stack, long set, VkBuf pos, VkBuf col)
	{
		VkDescriptorBufferInfo.Buffer posInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(pos.buffer).offset(0).range(VK_WHOLE_SIZE);
		VkDescriptorBufferInfo.Buffer colInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(col.buffer).offset(0).range(VK_WHOLE_SIZE);
		VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
		writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(posInfo);
		writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(colInfo);
		vkUpdateDescriptorSets(device, writes, null);
	}

	private void createRenderPass()
	{
		try (MemoryStack stack = stackPush())
		{
			VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(2, stack);
			att.get(0)
				.format(OUTPUT_FORMAT)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
				.storeOp(VK_ATTACHMENT_STORE_OP_STORE)
				.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
				.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
				.finalLayout(VK_IMAGE_LAYOUT_GENERAL);
			att.get(1)
				.format(DEPTH_FORMAT)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
				.storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
				.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
				.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
				.finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

			VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
			colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
			VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

			VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
			subpass.get(0)
				.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
				.colorAttachmentCount(1)
				.pColorAttachments(colorRef)
				.pDepthStencilAttachment(depthRef);

			// Hold the clear/writes until OpenGL's read of last frame is done (the wait semaphore lands
			// at colour-attachment output), and flush the colour write out for the compositor's read.
			VkSubpassDependency.Buffer deps = VkSubpassDependency.calloc(2, stack);
			deps.get(0)
				.srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
				.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
				.dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
				.srcAccessMask(0)
				.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
			deps.get(1)
				.srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
				.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
				.dstStageMask(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)
				.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
				.dstAccessMask(0);

			VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack).sType$Default()
				.pAttachments(att)
				.pSubpasses(subpass)
				.pDependencies(deps);
			LongBuffer pPass = stack.mallocLong(1);
			check(vkCreateRenderPass(device, info, null, pPass), "vkCreateRenderPass");
			renderPass = pPass.get(0);
		}
	}

	private void createPipeline()
	{
		try (MemoryStack stack = stackPush())
		{
			long vert = loadShaderModule("/rltx/raster.vert.spv");
			long frag = loadShaderModule("/rltx/raster.frag.spv");
			ByteBuffer main = stack.UTF8("main");

			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(main);
			stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(main);

			VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
			VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
				.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
			VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
				.viewportCount(1).scissorCount(1);
			VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
				.polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1f);
			VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
				.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
			VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
				.depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS);
			VkPipelineColorBlendAttachmentState.Buffer blendAtt = VkPipelineColorBlendAttachmentState.calloc(1, stack);
			blendAtt.get(0).blendEnable(false)
				.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
			VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendAtt);
			VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
				.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

			VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
			push.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(PUSH_BYTES);
			VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
				.pSetLayouts(stack.longs(descriptorSetLayout))
				.pPushConstantRanges(push);
			LongBuffer pLayout = stack.mallocLong(1);
			check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout), "vkCreatePipelineLayout");
			pipelineLayout = pLayout.get(0);

			VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
			info.get(0).sType$Default()
				.pStages(stages)
				.pVertexInputState(vertexInput)
				.pInputAssemblyState(assembly)
				.pViewportState(viewport)
				.pRasterizationState(raster)
				.pMultisampleState(multisample)
				.pDepthStencilState(depth)
				.pColorBlendState(blend)
				.pDynamicState(dynamic)
				.layout(pipelineLayout)
				.renderPass(renderPass)
				.subpass(0);
			LongBuffer pPipeline = stack.mallocLong(1);
			check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, info, null, pPipeline), "vkCreateGraphicsPipelines");
			pipeline = pPipeline.get(0);

			vkDestroyShaderModule(device, vert, null);
			vkDestroyShaderModule(device, frag, null);
		}
	}

	private long loadShaderModule(String resource)
	{
		try (InputStream in = NormalRenderer.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing shader resource " + resource);
			}
			byte[] bytes = in.readAllBytes();
			ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
			code.put(bytes).flip();
			try (MemoryStack stack = stackPush())
			{
				VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
				LongBuffer pModule = stack.mallocLong(1);
				check(vkCreateShaderModule(device, info, null, pModule), "vkCreateShaderModule");
				return pModule.get(0);
			}
			finally
			{
				MemoryUtil.memFree(code);
			}
		}
		catch (IOException e)
		{
			throw new IllegalStateException("Failed to read " + resource, e);
		}
	}

	// ---- static geometry ----

	@Override
	public void setStaticSet(int id, StaticScene scene, float[] transform)
	{
		staticScenes.put(id, scene);
		if (transform != null)
		{
			staticTransforms.put(id, transform);
		}
		else
		{
			staticTransforms.remove(id);
		}
		rebuildStatic();
	}

	@Override
	public void removeStaticSet(int id)
	{
		if (staticScenes.remove(id) != null)
		{
			staticTransforms.remove(id);
			rebuildStatic();
		}
	}

	@Override
	public boolean hasStaticSet(int id)
	{
		return staticScenes.containsKey(id);
	}

	// A zone changing in place is handled by the next full setStaticSet; live door/object edits will
	// lag until then, which the culling pass will address.
	@Override
	public boolean updateZone(int id, int zx, int zz, StaticScene.Zone zone)
	{
		return true;
	}

	// Rebuild the whole static buffer from every set the front end holds, baking each set's geometry
	// into world space. Called only on scene changes, so the per-vertex transform is not a hot path.
	private void rebuildStatic()
	{
		vkQueueWaitIdle(queue);
		FloatBuffer pos = staticPositions.mapped.asFloatBuffer();
		IntBuffer col = staticColors.mapped.asIntBuffer();
		int total = 0;
		for (Map.Entry<Integer, StaticScene> entry : staticScenes.entrySet())
		{
			float[] m = staticTransforms.get(entry.getKey());
			for (StaticScene.Zone zone : entry.getValue().zones)
			{
				if (zone == null)
				{
					continue;
				}
				int faces = zone.geometry.faces();
				if (total + faces > MAX_STATIC_FACES)
				{
					log.warn("Static face pool full at {} faces; dropping the rest of the scene", total);
					staticFaceCount = total;
					return;
				}
				bake(zone.geometry, m, faces, pos, col);
				total += faces;
			}
		}
		staticFaceCount = total;
	}

	private static void bake(GeometryBuffer g, float[] m, int faces, FloatBuffer pos, IntBuffer col)
	{
		int floats = faces * GeometryBuffer.FLOATS_PER_FACE;
		if (m == null)
		{
			pos.put(g.positions(), 0, floats);
		}
		else
		{
			float[] p = g.positions();
			for (int i = 0; i < floats; i += 3)
			{
				float x = p[i], y = p[i + 1], z = p[i + 2];
				pos.put(m[0] * x + m[1] * y + m[2] * z + m[3]);
				pos.put(m[4] * x + m[5] * y + m[6] * z + m[7]);
				pos.put(m[8] * x + m[9] * y + m[10] * z + m[11]);
			}
		}
		col.put(g.colors(), 0, faces);
	}

	// ---- output sizing and the handles the GL compositor shares ----

	@Override
	public boolean ensureOutput(int width, int height, float scale, int dlss, boolean rayReconstruction)
	{
		if (image != 0 && outputWidth == width && outputHeight == height)
		{
			return false;
		}
		vkQueueWaitIdle(queue);
		destroyTargets();
		createImage(width, height);
		createDepth(width, height);
		createFramebuffer(width, height);
		outputWidth = width;
		outputHeight = height;
		return true;
	}

	private void createImage(int width, int height)
	{
		try (MemoryStack stack = stackPush())
		{
			VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
				.handleTypes(ExternalHandles.MEMORY_HANDLE_TYPE);
			VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
				.pNext(external.address())
				.imageType(VK_IMAGE_TYPE_2D)
				.format(OUTPUT_FORMAT)
				.mipLevels(1)
				.arrayLayers(1)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			imageInfo.extent().width(width).height(height).depth(1);
			LongBuffer pImage = stack.mallocLong(1);
			check(vkCreateImage(device, imageInfo, null, pImage), "vkCreateImage");
			image = pImage.get(0);

			VkMemoryDedicatedRequirements dedicatedReq = VkMemoryDedicatedRequirements.calloc(stack).sType$Default();
			VkMemoryRequirements2 req = VkMemoryRequirements2.calloc(stack).sType$Default().pNext(dedicatedReq.address());
			VkImageMemoryRequirementsInfo2 reqInfo = VkImageMemoryRequirementsInfo2.calloc(stack).sType$Default().image(image);
			vkGetImageMemoryRequirements2(device, reqInfo, req);
			allocationSize = req.memoryRequirements().size();

			VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image);
			VkExportMemoryAllocateInfo exportAlloc = VkExportMemoryAllocateInfo.calloc(stack).sType$Default()
				.handleTypes(ExternalHandles.MEMORY_HANDLE_TYPE)
				.pNext(dedicated.address());
			VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default()
				.pNext(exportAlloc.address())
				.allocationSize(allocationSize)
				.memoryTypeIndex(ctx.findMemoryType(req.memoryRequirements().memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
			LongBuffer pMemory = stack.mallocLong(1);
			check(vkAllocateMemory(device, alloc, null, pMemory), "vkAllocateMemory");
			memory = pMemory.get(0);
			check(vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory");

			handle = ExternalHandles.exportMemory(device, stack, memory);

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
				.image(image)
				.viewType(VK_IMAGE_VIEW_TYPE_2D)
				.format(OUTPUT_FORMAT);
			viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
			LongBuffer pView = stack.mallocLong(1);
			check(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView");
			view = pView.get(0);
		}
	}

	private void createDepth(int width, int height)
	{
		try (MemoryStack stack = stackPush())
		{
			VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
				.imageType(VK_IMAGE_TYPE_2D)
				.format(DEPTH_FORMAT)
				.mipLevels(1)
				.arrayLayers(1)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			imageInfo.extent().width(width).height(height).depth(1);
			LongBuffer pImage = stack.mallocLong(1);
			check(vkCreateImage(device, imageInfo, null, pImage), "vkCreateImage depth");
			depthImage = pImage.get(0);

			VkMemoryRequirements2 req = VkMemoryRequirements2.calloc(stack).sType$Default();
			VkImageMemoryRequirementsInfo2 reqInfo = VkImageMemoryRequirementsInfo2.calloc(stack).sType$Default().image(depthImage);
			vkGetImageMemoryRequirements2(device, reqInfo, req);
			VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default()
				.allocationSize(req.memoryRequirements().size())
				.memoryTypeIndex(ctx.findMemoryType(req.memoryRequirements().memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
			LongBuffer pMemory = stack.mallocLong(1);
			check(vkAllocateMemory(device, alloc, null, pMemory), "vkAllocateMemory depth");
			depthMemory = pMemory.get(0);
			check(vkBindImageMemory(device, depthImage, depthMemory, 0), "vkBindImageMemory depth");

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
				.image(depthImage)
				.viewType(VK_IMAGE_VIEW_TYPE_2D)
				.format(DEPTH_FORMAT);
			viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
			LongBuffer pView = stack.mallocLong(1);
			check(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView depth");
			depthView = pView.get(0);
		}
	}

	private void createFramebuffer(int width, int height)
	{
		try (MemoryStack stack = stackPush())
		{
			VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack).sType$Default()
				.renderPass(renderPass)
				.pAttachments(stack.longs(view, depthView))
				.width(width)
				.height(height)
				.layers(1);
			LongBuffer pFb = stack.mallocLong(1);
			check(vkCreateFramebuffer(device, info, null, pFb), "vkCreateFramebuffer");
			framebuffer = pFb.get(0);
		}
	}

	private void destroyTargets()
	{
		if (framebuffer != 0)
		{
			vkDestroyFramebuffer(device, framebuffer, null);
			framebuffer = 0;
		}
		if (depthImage != 0)
		{
			vkDestroyImageView(device, depthView, null);
			vkDestroyImage(device, depthImage, null);
			vkFreeMemory(device, depthMemory, null);
			depthImage = depthMemory = depthView = 0;
		}
		if (image != 0)
		{
			vkDestroyImageView(device, view, null);
			vkDestroyImage(device, image, null);
			vkFreeMemory(device, memory, null);
			image = memory = view = handle = allocationSize = 0;
		}
	}

	@Override public long outputHandle() { return handle; }
	@Override public long outputAllocationSize() { return allocationSize; }
	@Override public long semaphoreVkDoneHandle() { return handleVkDone; }
	@Override public long semaphoreGlDoneHandle() { return handleGlDone; }
	@Override public int outputWidth() { return outputWidth; }
	@Override public int outputHeight() { return outputHeight; }
	@Override public int internalWidth() { return outputWidth; }
	@Override public int internalHeight() { return outputHeight; }

	// ---- drawing one frame ----

	@Override
	public void submit(FrameParams params, GeometryBuffer dynamic, GeometryBuffer translucent, GeometryBuffer water, boolean waitForGl, boolean signalGl)
	{
		// The login/idle screen sends frame.pattern with no scene background; Uber's trace shader draws
		// its gradient-checker from it, which this backend cannot reproduce yet. Approximate it with the
		// pattern's mid-grey so the screen is not black (the login probe expects a ~0x7f centre).
		float bgR, bgG, bgB;
		if (params.pattern)
		{
			bgR = bgG = bgB = 0.5f;
		}
		else
		{
			bgR = params.backgroundR;
			bgG = params.backgroundG;
			bgB = params.backgroundB;
		}

		waitPreviousFrame();

		// The previous frame's read of the dynamic buffer is done, so refill it. Only the opaque dynamic
		// geometry is drawn for now; translucent and water come with blending later.
		dynamicFaceCount = Math.min(dynamic.faces(), MAX_DYNAMIC_FACES);
		if (dynamicFaceCount > 0)
		{
			positions.mapped.asFloatBuffer().put(dynamic.positions(), 0, dynamicFaceCount * GeometryBuffer.FLOATS_PER_FACE);
			colors.mapped.asIntBuffer().put(dynamic.colors(), 0, dynamicFaceCount);
		}

		try (MemoryStack stack = stackPush())
		{
			check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
				.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer");

			VkClearValue.Buffer clears = VkClearValue.calloc(2, stack);
			clears.get(0).color().float32(stack.floats(bgR, bgG, bgB, 1f));
			clears.get(1).depthStencil().set(1f, 0);
			VkRect2D area = VkRect2D.calloc(stack);
			area.offset().set(0, 0);
			area.extent().set(outputWidth, outputHeight);
			VkRenderPassBeginInfo rp = VkRenderPassBeginInfo.calloc(stack).sType$Default()
				.renderPass(renderPass)
				.framebuffer(framebuffer)
				.renderArea(area)
				.pClearValues(clears);
			vkCmdBeginRenderPass(cmd, rp, VK_SUBPASS_CONTENTS_INLINE);

			VkViewport.Buffer vp = VkViewport.calloc(1, stack);
			vp.get(0).x(0f).y(0f).width(outputWidth).height(outputHeight).minDepth(0f).maxDepth(1f);
			vkCmdSetViewport(cmd, 0, vp);
			VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
			scissor.get(0).offset().set(0, 0);
			scissor.get(0).extent().set(outputWidth, outputHeight);
			vkCmdSetScissor(cmd, 0, scissor);

			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

			ByteBuffer pc = stack.malloc(PUSH_BYTES);
			pc.putFloat(params.cameraX).putFloat(params.cameraY).putFloat(params.cameraZ).putFloat(params.zoom);
			float[] r = params.forwardRotation;
			pc.putFloat(r[0]).putFloat(r[1]).putFloat(r[2]).putFloat(0f);
			pc.putFloat(r[3]).putFloat(r[4]).putFloat(r[5]).putFloat(0f);
			pc.putFloat(r[6]).putFloat(r[7]).putFloat(r[8]).putFloat(0f);
			pc.putFloat(outputWidth).putFloat(outputHeight).putFloat(NEAR).putFloat(FAR);
			pc.flip();
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pc);

			if (staticFaceCount > 0)
			{
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(staticDescriptorSet), null);
				vkCmdDraw(cmd, staticFaceCount * 3, 1, 0, 0);
			}
			if (dynamicFaceCount > 0)
			{
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(dynamicDescriptorSet), null);
				vkCmdDraw(cmd, dynamicFaceCount * 3, 1, 0, 0);
			}

			vkCmdEndRenderPass(cmd);
			check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

			VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
				.pCommandBuffers(stack.pointers(cmd));
			if (signalGl)
			{
				submit.pSignalSemaphores(stack.longs(semaphoreVkDone));
			}
			if (waitForGl)
			{
				submit.waitSemaphoreCount(1)
					.pWaitSemaphores(stack.longs(semaphoreGlDone))
					.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
			}
			check(vkQueueSubmit(queue, submit, fence), "vkQueueSubmit");
			fencePending = true;
		}
	}

	private void waitPreviousFrame()
	{
		if (!fencePending)
		{
			return;
		}
		long start = System.nanoTime();
		check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "vkWaitForFences");
		waitNanos += System.nanoTime() - start;
		check(vkResetFences(device, fence), "vkResetFences");
		fencePending = false;
	}

	@Override
	public void resetHistory()
	{
	}

	// ---- reading the frame back, and instrumentation ----

	@Override
	public int[] readbackOutput()
	{
		throw new UnsupportedOperationException("Normal mode does not implement photo readback yet");
	}

	@Override
	public float[] readbackColor()
	{
		throw new UnsupportedOperationException("Normal mode does not implement linear readback yet");
	}

	@Override
	public float readbackDepth(int x, int y)
	{
		throw new UnsupportedOperationException("Normal mode does not implement depth readback yet");
	}

	@Override public long waitNanos() { return waitNanos; }
	@Override public double averageLogLuminance() { return Double.NaN; }
	@Override public double lastGpuMillis() { return 0.0; }
	@Override public String passReport() { return "normal: raster " + staticFaceCount + "+" + dynamicFaceCount + " faces"; }

	// ---- lifecycle ----

	@Override
	public void destroy()
	{
		vkQueueWaitIdle(queue);
		destroyTargets();
		if (pipeline != 0) { vkDestroyPipeline(device, pipeline, null); pipeline = 0; }
		if (pipelineLayout != 0) { vkDestroyPipelineLayout(device, pipelineLayout, null); pipelineLayout = 0; }
		if (renderPass != 0) { vkDestroyRenderPass(device, renderPass, null); renderPass = 0; }
		if (descriptorPool != 0) { vkDestroyDescriptorPool(device, descriptorPool, null); descriptorPool = 0; }
		if (descriptorSetLayout != 0) { vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null); descriptorSetLayout = 0; }
		ctx.destroyBuffer(positions);
		ctx.destroyBuffer(colors);
		ctx.destroyBuffer(staticPositions);
		ctx.destroyBuffer(staticColors);
		if (fence != 0) { vkDestroyFence(device, fence, null); fence = 0; }
		if (semaphoreVkDone != 0) { vkDestroySemaphore(device, semaphoreVkDone, null); semaphoreVkDone = 0; }
		if (semaphoreGlDone != 0) { vkDestroySemaphore(device, semaphoreGlDone, null); semaphoreGlDone = 0; }
	}

	// ---- environment inputs still accepted and ignored until the raster path reaches them ----

	@Override public void setStaticView(int id, float[] transform, int minLevel, int level, int maxLevel, Set<Integer> hiddenRoofIds) { }
	@Override public void setSwayedZones(int id, boolean[] swayed) { }
	@Override public void setDisplacedZones(int id, boolean[] displaced) { }
	@Override public void setGroundRange(int first, int count) { }
	@Override public void setMaterials(float[] table) { }
	@Override public void setTextureArray(int layers, int size, int levels, ByteBuffer rgba) { }
	@Override public void setReliefArray(int layers, int size, int levels, ByteBuffer heights) { }
	@Override public void setTextureAnimation(float[] uvPerCycle) { }
	@Override public void setTerrainHeights(float[] heights) { }
	@Override public void setCells(int[] bits) { }
	@Override public void setWaterMask(int[] bits) { }
	@Override public void setSkybox(int width, int height, ByteBuffer rgba) { }
	@Override public void setStarMap(int width, int height, ByteBuffer rgba) { }
	@Override public void setAtmosphere(int width, int height, ByteBuffer rgba) { }
	@Override public void setMistGrid(float[] grid) { }
	@Override public void setLights(float[] packed, int count) { }
	@Override public void setGuide(float[] packed, int floats) { }
	@Override public void setPlumes(float[] packed, int floats) { }
	@Override public void setTrees(float[] packed, int floats) { }
	@Override public void setPrints(float[] packed, int floats) { }
	@Override public void setMarkers(float[] packed, int floats) { }
	@Override public void setRunoff(float[] packed) { }
	@Override public void setRipples(float[] packed, int floats) { }
	@Override public void setUiMask(int[] pixels, int width, int height, int keyRgb) { }
}
