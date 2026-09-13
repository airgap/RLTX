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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
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
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageMemoryRequirementsInfo2;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
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
import org.lwjgl.vulkan.VkSamplerCreateInfo;
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
 * <p>Rasterises the static world and the per-frame dynamic geometry with the camera matched to
 * {@code trace.comp}'s pinhole convention, z-buffered through a depth image. The static scene is
 * baked to world space and culled by level and roof; faces are textured from the game's texture
 * array, modulating each face's baked colour; and the distance fades into the scene's fog colour.
 * Still to come: a lighting model, translucency, water, and the fuller sky, bloom and colour grade
 * of the shared post chain. Photo and readback paths fail loudly rather than return a blank frame.
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
	private static final int PUSH_BYTES = 112;
	// Byte offset of the fragment shader's translucent flag (fogRange.z) inside the push block, so the
	// translucent pass can flip it without repacking the whole struct.
	private static final int TRANSLUCENT_FLAG_OFFSET = 104;
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
	private long descriptorSetLayout, descriptorPool, dynamicDescriptorSet, staticDescriptorSet, translucentDescriptorSet;
	private long pipelineLayout, pipeline, blendPipeline;
	private long skyPipelineLayout, skyPipeline;
	private static final int SKY_PUSH_BYTES = 80;

	// Dynamic geometry is written to host-visible staging each frame and copied into the device-local
	// buffers the vertex shader reads; static is device-local too, uploaded once per scene change.
	// Device-local memory is why this is not the PCIe-bound crawl a host-visible shader read would be.
	private VkBuf dynamicStagingPos, dynamicStagingCol, dynamicStagingUv, dynamicStagingTex;
	private VkBuf positions, colors, uvs, texs;
	private int dynamicFaceCount;

	// The per-frame translucent geometry, alpha-blended after the opaque pass; its staging, device-local
	// buffers and descriptor set mirror the opaque dynamic ones above.
	private VkBuf translucentStagingPos, translucentStagingCol, translucentStagingUv, translucentStagingTex;
	private VkBuf translucentPositions, translucentColors, translucentUvs, translucentTexs;
	private int translucentFaceCount;

	private VkBuf staticPositions, staticColors, staticUvs, staticTexs;
	private int staticFaceCount;

	// The game's texture array (sampler2DArray at binding 4) and its sampler. A 1x1 dummy stands in
	// until setTextureArray uploads the real one, so the sampler binding is always valid.
	private long textureImage, textureMemory, textureView, textureSampler;
	// The scenes the front end has handed us, by id; the static buffer is rebuilt from all of them
	// on any change. The transform places a set's local geometry into the world (null is identity).
	private final Map<Integer, float[]> staticTransforms = new HashMap<>();
	private final Map<Integer, StaticScene> staticScenes = new HashMap<>();

	// Per baked group, its vertex range in the static buffer and the level/roof/set that decide
	// whether it is drawn this frame. The view of each set (which levels and roofs are visible) comes
	// from setStaticView; groups are recorded in buffer order so visible ones coalesce into few draws.
	private int[] groupFirstVertex, groupVertexCount, groupLevel, groupRoof, groupSet;
	// Per recorded group, whether it is translucent, so the opaque and translucent passes can split them.
	private boolean[] groupTranslucent;
	private int groupCount;
	private final Map<Integer, View> views = new HashMap<>();

	private static final class View
	{
		int minLevel = 0, level = 3, maxLevel = 3;
		Set<Integer> hiddenRoofIds = Collections.emptySet();
		final boolean[] levelHasRoofs = new boolean[4];
	}

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
		createSampler();
		ByteBuffer dummy = whitePixel();
		uploadTextureArray(1, 1, 1, dummy);
		MemoryUtil.memFree(dummy);
		createRenderPass();
		createPipeline();
		createSkyPipeline();
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

	private static final int HOST = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

	private void createGeometryBuffers()
	{
		int deviceStorage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
		int device = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
		long dynPos = (long) MAX_DYNAMIC_FACES * GeometryBuffer.FLOATS_PER_FACE * Float.BYTES;
		long dynCol = (long) MAX_DYNAMIC_FACES * Integer.BYTES;
		long dynUv = (long) MAX_DYNAMIC_FACES * GeometryBuffer.UV_FLOATS_PER_FACE * Float.BYTES;
		dynamicStagingPos = ctx.createBuffer(dynPos, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		dynamicStagingCol = ctx.createBuffer(dynCol, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		dynamicStagingUv = ctx.createBuffer(dynUv, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		dynamicStagingTex = ctx.createBuffer(dynCol, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		positions = ctx.createBuffer(dynPos, deviceStorage, device);
		colors = ctx.createBuffer(dynCol, deviceStorage, device);
		uvs = ctx.createBuffer(dynUv, deviceStorage, device);
		texs = ctx.createBuffer(dynCol, deviceStorage, device);
		translucentStagingPos = ctx.createBuffer(dynPos, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		translucentStagingCol = ctx.createBuffer(dynCol, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		translucentStagingUv = ctx.createBuffer(dynUv, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		translucentStagingTex = ctx.createBuffer(dynCol, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		translucentPositions = ctx.createBuffer(dynPos, deviceStorage, device);
		translucentColors = ctx.createBuffer(dynCol, deviceStorage, device);
		translucentUvs = ctx.createBuffer(dynUv, deviceStorage, device);
		translucentTexs = ctx.createBuffer(dynCol, deviceStorage, device);
		staticPositions = ctx.createBuffer((long) MAX_STATIC_FACES * GeometryBuffer.FLOATS_PER_FACE * Float.BYTES, deviceStorage, device);
		staticColors = ctx.createBuffer((long) MAX_STATIC_FACES * Integer.BYTES, deviceStorage, device);
		staticUvs = ctx.createBuffer((long) MAX_STATIC_FACES * GeometryBuffer.UV_FLOATS_PER_FACE * Float.BYTES, deviceStorage, device);
		staticTexs = ctx.createBuffer((long) MAX_STATIC_FACES * Integer.BYTES, deviceStorage, device);
	}

	private void createDescriptors()
	{
		try (MemoryStack stack = stackPush())
		{
			VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(5, stack);
			for (int i = 0; i < 4; ++i)
			{
				binds.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
			}
			binds.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
			VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
			LongBuffer pLayout = stack.mallocLong(1);
			check(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout), "vkCreateDescriptorSetLayout");
			descriptorSetLayout = pLayout.get(0);

			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
			sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(12);
			sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3);
			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(3).pPoolSizes(sizes);
			LongBuffer pPool = stack.mallocLong(1);
			check(vkCreateDescriptorPool(device, poolInfo, null, pPool), "vkCreateDescriptorPool");
			descriptorPool = pPool.get(0);

			dynamicDescriptorSet = allocateSet(stack);
			staticDescriptorSet = allocateSet(stack);
			translucentDescriptorSet = allocateSet(stack);
			writeSet(stack, dynamicDescriptorSet, positions, colors, uvs, texs);
			writeSet(stack, staticDescriptorSet, staticPositions, staticColors, staticUvs, staticTexs);
			writeSet(stack, translucentDescriptorSet, translucentPositions, translucentColors, translucentUvs, translucentTexs);
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

	private void writeSet(MemoryStack stack, long set, VkBuf pos, VkBuf col, VkBuf uv, VkBuf tex)
	{
		VkBuf[] bufs = {pos, col, uv, tex};
		VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
		for (int i = 0; i < 4; ++i)
		{
			VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack).buffer(bufs[i].buffer).offset(0).range(VK_WHOLE_SIZE);
			writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bi);
		}
		vkUpdateDescriptorSets(device, writes, null);
	}

	private void createSampler()
	{
		try (MemoryStack stack = stackPush())
		{
			VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
				.magFilter(VK_FILTER_LINEAR)
				.minFilter(VK_FILTER_LINEAR)
				.mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
				.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.maxLod(VK_LOD_CLAMP_NONE);
			LongBuffer pSampler = stack.mallocLong(1);
			check(vkCreateSampler(device, info, null, pSampler), "vkCreateSampler");
			textureSampler = pSampler.get(0);
		}
	}

	private static ByteBuffer whitePixel()
	{
		ByteBuffer b = MemoryUtil.memAlloc(4);
		b.put((byte) 0x7f).put((byte) 0x7f).put((byte) 0x7f).put((byte) 0xff).flip();
		return b;
	}

	// Uploads the game's texture array (or the 1x1 stand-in) and points both descriptor sets at it.
	// Layout is level-major: level 0 for all layers, then level 1 for all layers, and so on, the same
	// packing the tracer's uploadArray expects. Called on the client thread, so blocking is fine.
	@Override
	public void setTextureArray(int layers, int size, int levels, ByteBuffer rgba)
	{
		uploadTextureArray(layers, size, levels, rgba);
	}

	private void uploadTextureArray(int layers, int size, int levels, ByteBuffer rgba)
	{
		vkQueueWaitIdle(queue);
		destroyTexture();
		try (MemoryStack stack = stackPush())
		{
			VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
				.imageType(VK_IMAGE_TYPE_2D)
				.format(OUTPUT_FORMAT)
				.mipLevels(levels)
				.arrayLayers(layers)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			imageInfo.extent().width(size).height(size).depth(1);
			LongBuffer pImage = stack.mallocLong(1);
			check(vkCreateImage(device, imageInfo, null, pImage), "vkCreateImage texture");
			textureImage = pImage.get(0);

			VkMemoryRequirements2 req = VkMemoryRequirements2.calloc(stack).sType$Default();
			VkImageMemoryRequirementsInfo2 reqInfo = VkImageMemoryRequirementsInfo2.calloc(stack).sType$Default().image(textureImage);
			vkGetImageMemoryRequirements2(device, reqInfo, req);
			VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default()
				.allocationSize(req.memoryRequirements().size())
				.memoryTypeIndex(ctx.findMemoryType(req.memoryRequirements().memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
			LongBuffer pMemory = stack.mallocLong(1);
			check(vkAllocateMemory(device, alloc, null, pMemory), "vkAllocateMemory texture");
			textureMemory = pMemory.get(0);
			check(vkBindImageMemory(device, textureImage, textureMemory, 0), "vkBindImageMemory texture");

			long bytes = 0;
			for (int level = 0; level < levels; ++level)
			{
				bytes += (long) layers * (size >> level) * (size >> level) * 4;
			}
			VkBuf staging = ctx.createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
			MemoryUtil.memCopy(MemoryUtil.memAddress(rgba), MemoryUtil.memAddress(staging.mapped), bytes);

			VkCommandBuffer up = ctx.beginOneTime(commandPool);
			textureBarrier(up, layers, levels, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
			VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(levels, stack);
			long offset = 0;
			for (int level = 0; level < levels; ++level)
			{
				int extent = size >> level;
				regions.get(level).bufferOffset(offset).bufferRowLength(0).bufferImageHeight(0);
				regions.get(level).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0).layerCount(layers);
				regions.get(level).imageExtent().width(extent).height(extent).depth(1);
				offset += (long) layers * extent * extent * 4;
			}
			vkCmdCopyBufferToImage(up, staging.buffer, textureImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
			textureBarrier(up, layers, levels, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
				VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
			ctx.endOneTimeAndWait(up, queue, commandPool);
			ctx.destroyBuffer(staging);
			// The rgba buffer belongs to the caller — the plugin reuses it for the avatar renderer and
			// frees it itself — so it is not freed here, matching RtRenderer.uploadArray.

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
				.image(textureImage)
				.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
				.format(OUTPUT_FORMAT);
			viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(levels).baseArrayLayer(0).layerCount(layers);
			LongBuffer pView = stack.mallocLong(1);
			check(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView texture");
			textureView = pView.get(0);

			VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack)
				.sampler(textureSampler).imageView(textureView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
			writes.get(0).sType$Default().dstSet(dynamicDescriptorSet).dstBinding(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imgInfo);
			writes.get(1).sType$Default().dstSet(staticDescriptorSet).dstBinding(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imgInfo);
			writes.get(2).sType$Default().dstSet(translucentDescriptorSet).dstBinding(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imgInfo);
			vkUpdateDescriptorSets(device, writes, null);
		}
	}

	private void textureBarrier(VkCommandBuffer c, int layers, int levels, int oldLayout, int newLayout, int srcStage, int srcAccess, int dstStage, int dstAccess)
	{
		try (MemoryStack stack = stackPush())
		{
			VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
			b.get(0).sType$Default()
				.srcAccessMask(srcAccess).dstAccessMask(dstAccess)
				.oldLayout(oldLayout).newLayout(newLayout)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.image(textureImage);
			b.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(levels).baseArrayLayer(0).layerCount(layers);
			vkCmdPipelineBarrier(c, srcStage, dstStage, 0, null, null, b);
		}
	}

	private void destroyTexture()
	{
		if (textureImage != 0)
		{
			vkDestroyImageView(device, textureView, null);
			vkDestroyImage(device, textureImage, null);
			vkFreeMemory(device, textureMemory, null);
			textureImage = textureMemory = textureView = 0;
		}
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
			// Opaque writes depth; the translucent variant tests against it but does not write, so blended
			// faces sort against the solid world without occluding one another by depth.
			VkPipelineDepthStencilStateCreateInfo depthOpaque = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
				.depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS);
			VkPipelineDepthStencilStateCreateInfo depthBlend = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
				.depthTestEnable(true).depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_LESS);
			int writeAll = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
			VkPipelineColorBlendAttachmentState.Buffer blendAttOpaque = VkPipelineColorBlendAttachmentState.calloc(1, stack);
			blendAttOpaque.get(0).blendEnable(false).colorWriteMask(writeAll);
			VkPipelineColorBlendAttachmentState.Buffer blendAttTrans = VkPipelineColorBlendAttachmentState.calloc(1, stack);
			blendAttTrans.get(0).blendEnable(true).colorWriteMask(writeAll)
				.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
				.srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO).alphaBlendOp(VK_BLEND_OP_ADD);
			VkPipelineColorBlendStateCreateInfo blendOpaque = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendAttOpaque);
			VkPipelineColorBlendStateCreateInfo blendTrans = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendAttTrans);
			VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
				.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

			VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
			push.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(PUSH_BYTES);
			VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
				.pSetLayouts(stack.longs(descriptorSetLayout))
				.pPushConstantRanges(push);
			LongBuffer pLayout = stack.mallocLong(1);
			check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout), "vkCreatePipelineLayout");
			pipelineLayout = pLayout.get(0);

			// Two pipelines from one layout and shader pair: the opaque one, then the alpha-blended
			// variant that differs only in its depth-write and colour-blend state.
			VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(2, stack);
			info.get(0).sType$Default()
				.pStages(stages)
				.pVertexInputState(vertexInput)
				.pInputAssemblyState(assembly)
				.pViewportState(viewport)
				.pRasterizationState(raster)
				.pMultisampleState(multisample)
				.pDepthStencilState(depthOpaque)
				.pColorBlendState(blendOpaque)
				.pDynamicState(dynamic)
				.layout(pipelineLayout)
				.renderPass(renderPass)
				.subpass(0);
			info.get(1).sType$Default()
				.pStages(stages)
				.pVertexInputState(vertexInput)
				.pInputAssemblyState(assembly)
				.pViewportState(viewport)
				.pRasterizationState(raster)
				.pMultisampleState(multisample)
				.pDepthStencilState(depthBlend)
				.pColorBlendState(blendTrans)
				.pDynamicState(dynamic)
				.layout(pipelineLayout)
				.renderPass(renderPass)
				.subpass(0);
			LongBuffer pPipeline = stack.mallocLong(2);
			check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, info, null, pPipeline), "vkCreateGraphicsPipelines");
			pipeline = pPipeline.get(0);
			blendPipeline = pPipeline.get(1);

			vkDestroyShaderModule(device, vert, null);
			vkDestroyShaderModule(device, frag, null);
		}
	}

	private void createSkyPipeline()
	{
		try (MemoryStack stack = stackPush())
		{
			long vert = loadShaderModule("/rltx/sky.vert.spv");
			long frag = loadShaderModule("/rltx/sky.frag.spv");
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
			// No depth test or write: the sky fills the background and geometry draws over it.
			VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
				.depthTestEnable(false).depthWriteEnable(false);
			VkPipelineColorBlendAttachmentState.Buffer blendAtt = VkPipelineColorBlendAttachmentState.calloc(1, stack);
			blendAtt.get(0).blendEnable(false)
				.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
			VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendAtt);
			VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
				.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

			VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
			push.get(0).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(SKY_PUSH_BYTES);
			VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
				.pPushConstantRanges(push);
			LongBuffer pLayout = stack.mallocLong(1);
			check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout), "vkCreatePipelineLayout sky");
			skyPipelineLayout = pLayout.get(0);

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
				.layout(skyPipelineLayout)
				.renderPass(renderPass)
				.subpass(0);
			LongBuffer pPipeline = stack.mallocLong(1);
			check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, info, null, pPipeline), "vkCreateGraphicsPipelines sky");
			skyPipeline = pPipeline.get(0);

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
			views.remove(id);
			rebuildStatic();
		}
	}

	@Override
	public void setStaticView(int id, float[] transform, int minLevel, int level, int maxLevel, Set<Integer> hiddenRoofIds)
	{
		View v = views.computeIfAbsent(id, k -> new View());
		v.minLevel = minLevel;
		v.level = level;
		v.maxLevel = maxLevel;
		v.hiddenRoofIds = hiddenRoofIds == null ? Collections.emptySet() : hiddenRoofIds;
	}

	// The GPU plugin's rule: whole levels within range draw, minus roofs above the current level the
	// client asked to hide.
	private static boolean groupVisible(View v, int level, int roof)
	{
		if (level < v.minLevel || level > v.maxLevel)
		{
			return false;
		}
		if (roof == 0 || level >= v.levelHasRoofs.length || !v.levelHasRoofs[level] || v.hiddenRoofIds.isEmpty() || level <= v.level)
		{
			return true;
		}
		return !v.hiddenRoofIds.contains(roof);
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
		int total = 0, groups = 0;
		for (StaticScene scene : staticScenes.values())
		{
			for (StaticScene.Zone zone : scene.zones)
			{
				if (zone != null)
				{
					total += zone.geometry.faces();
					groups += zone.groupCount();
				}
			}
		}
		if (total > MAX_STATIC_FACES)
		{
			log.warn("Static geometry is {} faces, above the {}-face cap; the rest is dropped", total, MAX_STATIC_FACES);
			total = MAX_STATIC_FACES;
		}
		staticFaceCount = total;
		groupFirstVertex = new int[groups];
		groupVertexCount = new int[groups];
		groupLevel = new int[groups];
		groupRoof = new int[groups];
		groupSet = new int[groups];
		groupTranslucent = new boolean[groups];
		groupCount = 0;
		for (View v : views.values())
		{
			java.util.Arrays.fill(v.levelHasRoofs, false);
		}
		if (total == 0)
		{
			return;
		}

		// Bake every set's zones into host-visible staging, then copy once into device-local VRAM,
		// recording each group's vertex range and level/roof so the draw can cull to the visible set.
		VkBuf posStage = ctx.createBuffer((long) total * GeometryBuffer.FLOATS_PER_FACE * Float.BYTES, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		VkBuf colStage = ctx.createBuffer((long) total * Integer.BYTES, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		VkBuf uvStage = ctx.createBuffer((long) total * GeometryBuffer.UV_FLOATS_PER_FACE * Float.BYTES, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		VkBuf texStage = ctx.createBuffer((long) total * Integer.BYTES, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, HOST);
		FloatBuffer pos = posStage.mapped.asFloatBuffer();
		IntBuffer col = colStage.mapped.asIntBuffer();
		FloatBuffer uv = uvStage.mapped.asFloatBuffer();
		IntBuffer tex = texStage.mapped.asIntBuffer();
		int written = 0;
		for (Map.Entry<Integer, StaticScene> entry : staticScenes.entrySet())
		{
			int id = entry.getKey();
			float[] m = staticTransforms.get(id);
			View v = views.computeIfAbsent(id, k -> new View());
			for (StaticScene.Zone zone : entry.getValue().zones)
			{
				if (zone == null || written >= total)
				{
					continue;
				}
				int zoneFaces = Math.min(zone.geometry.faces(), total - written);
				for (int g = 0; g < zone.groupCount(); ++g)
				{
					int base = zone.groupFaceBase[g];
					int count = zone.groupFaceCount[g];
					if (base >= zoneFaces)
					{
						continue;
					}
					if (base + count > zoneFaces)
					{
						count = zoneFaces - base;
					}
					// Water is left to a separate feature: its faces stay baked in the buffer but are
					// recorded by no group, so neither the opaque nor the translucent pass draws them.
					if (zone.groupWater[g])
					{
						continue;
					}
					int lvl = zone.groupLevel[g];
					int roof = zone.groupRoofId[g];
					groupFirstVertex[groupCount] = (written + base) * 3;
					groupVertexCount[groupCount] = count * 3;
					groupLevel[groupCount] = lvl;
					groupRoof[groupCount] = roof;
					groupSet[groupCount] = id;
					groupTranslucent[groupCount] = zone.groupTranslucent[g];
					++groupCount;
					if (roof > 0 && lvl >= 0 && lvl < v.levelHasRoofs.length)
					{
						v.levelHasRoofs[lvl] = true;
					}
				}
				bake(zone.geometry, m, zoneFaces, pos, col, uv, tex);
				written += zoneFaces;
			}
		}

		VkCommandBuffer up = ctx.beginOneTime(commandPool);
		try (MemoryStack stack = stackPush())
		{
			copy(up, stack, posStage, staticPositions, (long) total * GeometryBuffer.FLOATS_PER_FACE * Float.BYTES);
			copy(up, stack, colStage, staticColors, (long) total * Integer.BYTES);
			copy(up, stack, uvStage, staticUvs, (long) total * GeometryBuffer.UV_FLOATS_PER_FACE * Float.BYTES);
			copy(up, stack, texStage, staticTexs, (long) total * Integer.BYTES);
		}
		ctx.endOneTimeAndWait(up, queue, commandPool);
		ctx.destroyBuffer(posStage);
		ctx.destroyBuffer(colStage);
		ctx.destroyBuffer(uvStage);
		ctx.destroyBuffer(texStage);
	}

	private static void copy(VkCommandBuffer c, MemoryStack stack, VkBuf src, VkBuf dst, long bytes)
	{
		VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).size(bytes);
		vkCmdCopyBuffer(c, src.buffer, dst.buffer, region);
	}

	private static void bake(GeometryBuffer g, float[] m, int faces, FloatBuffer pos, IntBuffer col, FloatBuffer uv, IntBuffer tex)
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
		uv.put(g.uvs(), 0, faces * GeometryBuffer.UV_FLOATS_PER_FACE);
		tex.put(g.textures(), 0, faces);
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
		// Distance fog fades geometry into the scene's background colour over the far part of the render
		// distance, and the frame clears to that same colour so scenery beyond it fades away seamlessly.
		// Background is what the client shows where no geometry is; the fog horizon colour is Uber's
		// procedural-sky match and reads wrong as a flat fill, so it is not used here. With no render
		// distance set there is no fade, and the login/idle screen (frame.pattern) clears to a mid-grey.
		float fogR = params.backgroundR;
		float fogG = params.backgroundG;
		float fogB = params.backgroundB;
		float fogStart, fogEnd;
		if (params.renderDistance > 0f)
		{
			fogEnd = params.renderDistance;
			fogStart = params.renderDistance * (1f - Math.min(Math.max(params.distanceFade, 0f), 1f));
		}
		else
		{
			fogStart = fogEnd = 1e9f;
		}

		float bgR, bgG, bgB;
		if (params.pattern)
		{
			bgR = bgG = bgB = 0.5f;
		}
		else
		{
			bgR = fogR;
			bgG = fogG;
			bgB = fogB;
		}

		waitPreviousFrame();

		// The previous frame's reads of the dynamic buffers are done, so refill them: opaque geometry into
		// the dynamic buffers, translucent geometry into its own. Water is skipped until it has a pass.
		dynamicFaceCount = Math.min(dynamic.faces(), MAX_DYNAMIC_FACES);
		if (dynamicFaceCount > 0)
		{
			dynamicStagingPos.mapped.asFloatBuffer().put(dynamic.positions(), 0, dynamicFaceCount * GeometryBuffer.FLOATS_PER_FACE);
			dynamicStagingCol.mapped.asIntBuffer().put(dynamic.colors(), 0, dynamicFaceCount);
			dynamicStagingUv.mapped.asFloatBuffer().put(dynamic.uvs(), 0, dynamicFaceCount * GeometryBuffer.UV_FLOATS_PER_FACE);
			dynamicStagingTex.mapped.asIntBuffer().put(dynamic.textures(), 0, dynamicFaceCount);
		}
		translucentFaceCount = Math.min(translucent.faces(), MAX_DYNAMIC_FACES);
		if (translucentFaceCount > 0)
		{
			translucentStagingPos.mapped.asFloatBuffer().put(translucent.positions(), 0, translucentFaceCount * GeometryBuffer.FLOATS_PER_FACE);
			translucentStagingCol.mapped.asIntBuffer().put(translucent.colors(), 0, translucentFaceCount);
			translucentStagingUv.mapped.asFloatBuffer().put(translucent.uvs(), 0, translucentFaceCount * GeometryBuffer.UV_FLOATS_PER_FACE);
			translucentStagingTex.mapped.asIntBuffer().put(translucent.textures(), 0, translucentFaceCount);
		}

		try (MemoryStack stack = stackPush())
		{
			check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
				.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer");

			if (dynamicFaceCount > 0)
			{
				copy(cmd, stack, dynamicStagingPos, positions, (long) dynamicFaceCount * GeometryBuffer.FLOATS_PER_FACE * Float.BYTES);
				copy(cmd, stack, dynamicStagingCol, colors, (long) dynamicFaceCount * Integer.BYTES);
				copy(cmd, stack, dynamicStagingUv, uvs, (long) dynamicFaceCount * GeometryBuffer.UV_FLOATS_PER_FACE * Float.BYTES);
				copy(cmd, stack, dynamicStagingTex, texs, (long) dynamicFaceCount * Integer.BYTES);
			}
			if (translucentFaceCount > 0)
			{
				copy(cmd, stack, translucentStagingPos, translucentPositions, (long) translucentFaceCount * GeometryBuffer.FLOATS_PER_FACE * Float.BYTES);
				copy(cmd, stack, translucentStagingCol, translucentColors, (long) translucentFaceCount * Integer.BYTES);
				copy(cmd, stack, translucentStagingUv, translucentUvs, (long) translucentFaceCount * GeometryBuffer.UV_FLOATS_PER_FACE * Float.BYTES);
				copy(cmd, stack, translucentStagingTex, translucentTexs, (long) translucentFaceCount * Integer.BYTES);
			}
			if (dynamicFaceCount > 0 || translucentFaceCount > 0)
			{
				VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack).sType$Default()
					.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
				vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, 0, mb, null, null);
			}

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

			// The sky fills the background before geometry draws over it; the login/idle pattern screen
			// keeps its flat clear instead.
			if (!params.pattern)
			{
				vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, skyPipeline);
				ByteBuffer sky = stack.malloc(SKY_PUSH_BYTES);
				float[] inv = params.inverseRotation;
				sky.putFloat(inv[0]).putFloat(inv[1]).putFloat(inv[2]).putFloat(0f);
				sky.putFloat(inv[3]).putFloat(inv[4]).putFloat(inv[5]).putFloat(0f);
				sky.putFloat(inv[6]).putFloat(inv[7]).putFloat(inv[8]).putFloat(0f);
				sky.putFloat(params.zoom).putFloat(outputWidth).putFloat(outputHeight).putFloat(params.sunUp);
				sky.putFloat(params.sunX).putFloat(params.sunY).putFloat(params.sunZ).putFloat(params.sunIntensity);
				sky.flip();
				vkCmdPushConstants(cmd, skyPipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sky);
				vkCmdDraw(cmd, 3, 1, 0, 0);
			}

			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

			ByteBuffer pc = stack.malloc(PUSH_BYTES);
			pc.putFloat(params.cameraX).putFloat(params.cameraY).putFloat(params.cameraZ).putFloat(params.zoom);
			float[] r = params.forwardRotation;
			pc.putFloat(r[0]).putFloat(r[1]).putFloat(r[2]).putFloat(0f);
			pc.putFloat(r[3]).putFloat(r[4]).putFloat(r[5]).putFloat(0f);
			pc.putFloat(r[6]).putFloat(r[7]).putFloat(r[8]).putFloat(0f);
			pc.putFloat(outputWidth).putFloat(outputHeight).putFloat(NEAR).putFloat(FAR);
			pc.putFloat(fogR).putFloat(fogG).putFloat(fogB).putFloat(1f);
			// fogRange.z is the translucent flag the fragment shader reads: 0 opaque here, 1 for the pass below.
			pc.putFloat(fogStart).putFloat(fogEnd).putFloat(0f).putFloat(0f);
			pc.flip();
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pc);

			// Opaque pass: solid static groups, then the opaque dynamic buffer.
			if (staticFaceCount > 0)
			{
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(staticDescriptorSet), null);
				drawStaticGroups(cmd, false);
			}
			if (dynamicFaceCount > 0)
			{
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(dynamicDescriptorSet), null);
				vkCmdDraw(cmd, dynamicFaceCount * 3, 1, 0, 0);
			}

			// Translucent pass over the opaque frame: same layout, the blend pipeline (depth-tested, no
			// depth write), with the flag flipped so the fragment shader emits the real opacity.
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, blendPipeline);
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, TRANSLUCENT_FLAG_OFFSET, stack.floats(1f));
			if (staticFaceCount > 0)
			{
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(staticDescriptorSet), null);
				drawStaticGroups(cmd, true);
			}
			if (translucentFaceCount > 0)
			{
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(translucentDescriptorSet), null);
				vkCmdDraw(cmd, translucentFaceCount * 3, 1, 0, 0);
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

	// Draws the recorded static groups of one opacity class that the current view keeps, coalescing
	// consecutive visible ones into a single call. Water groups were never recorded, so they never draw;
	// the caller has bound the pipeline, push flag and static descriptor set for the wanted class.
	private void drawStaticGroups(VkCommandBuffer cmd, boolean wantTranslucent)
	{
		int runStart = -1, runCount = 0;
		for (int i = 0; i < groupCount; ++i)
		{
			if (groupTranslucent[i] != wantTranslucent)
			{
				continue;
			}
			View v = views.get(groupSet[i]);
			boolean visible = v == null || groupVisible(v, groupLevel[i], groupRoof[i]);
			if (visible && runStart >= 0 && groupFirstVertex[i] == runStart + runCount)
			{
				runCount += groupVertexCount[i];
			}
			else
			{
				if (runStart >= 0)
				{
					vkCmdDraw(cmd, runCount, 1, runStart, 0);
					runStart = -1;
				}
				if (visible)
				{
					runStart = groupFirstVertex[i];
					runCount = groupVertexCount[i];
				}
			}
		}
		if (runStart >= 0)
		{
			vkCmdDraw(cmd, runCount, 1, runStart, 0);
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
	@Override public String passReport() { return "normal: raster " + staticFaceCount + "+" + dynamicFaceCount + " faces, +" + translucentFaceCount + " translucent"; }

	// ---- lifecycle ----

	@Override
	public void destroy()
	{
		vkQueueWaitIdle(queue);
		destroyTargets();
		if (pipeline != 0) { vkDestroyPipeline(device, pipeline, null); pipeline = 0; }
		if (blendPipeline != 0) { vkDestroyPipeline(device, blendPipeline, null); blendPipeline = 0; }
		if (pipelineLayout != 0) { vkDestroyPipelineLayout(device, pipelineLayout, null); pipelineLayout = 0; }
		if (skyPipeline != 0) { vkDestroyPipeline(device, skyPipeline, null); skyPipeline = 0; }
		if (skyPipelineLayout != 0) { vkDestroyPipelineLayout(device, skyPipelineLayout, null); skyPipelineLayout = 0; }
		if (renderPass != 0) { vkDestroyRenderPass(device, renderPass, null); renderPass = 0; }
		if (descriptorPool != 0) { vkDestroyDescriptorPool(device, descriptorPool, null); descriptorPool = 0; }
		if (descriptorSetLayout != 0) { vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null); descriptorSetLayout = 0; }
		destroyTexture();
		if (textureSampler != 0) { vkDestroySampler(device, textureSampler, null); textureSampler = 0; }
		ctx.destroyBuffer(dynamicStagingPos);
		ctx.destroyBuffer(dynamicStagingCol);
		ctx.destroyBuffer(dynamicStagingUv);
		ctx.destroyBuffer(dynamicStagingTex);
		ctx.destroyBuffer(positions);
		ctx.destroyBuffer(colors);
		ctx.destroyBuffer(uvs);
		ctx.destroyBuffer(texs);
		ctx.destroyBuffer(translucentStagingPos);
		ctx.destroyBuffer(translucentStagingCol);
		ctx.destroyBuffer(translucentStagingUv);
		ctx.destroyBuffer(translucentStagingTex);
		ctx.destroyBuffer(translucentPositions);
		ctx.destroyBuffer(translucentColors);
		ctx.destroyBuffer(translucentUvs);
		ctx.destroyBuffer(translucentTexs);
		ctx.destroyBuffer(staticPositions);
		ctx.destroyBuffer(staticColors);
		ctx.destroyBuffer(staticUvs);
		ctx.destroyBuffer(staticTexs);
		if (fence != 0) { vkDestroyFence(device, fence, null); fence = 0; }
		if (semaphoreVkDone != 0) { vkDestroySemaphore(device, semaphoreVkDone, null); semaphoreVkDone = 0; }
		if (semaphoreGlDone != 0) { vkDestroySemaphore(device, semaphoreGlDone, null); semaphoreGlDone = 0; }
	}

	// ---- environment inputs still accepted and ignored until the raster path reaches them ----

	@Override public void setSwayedZones(int id, boolean[] swayed) { }
	@Override public void setDisplacedZones(int id, boolean[] displaced) { }
	@Override public void setGroundRange(int first, int count) { }
	@Override public void setMaterials(float[] table) { }
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
