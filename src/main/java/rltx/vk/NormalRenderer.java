package rltx.vk;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;
import static rltx.vk.VkUtil.check;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageMemoryRequirementsInfo2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedRequirements;
import org.lwjgl.vulkan.VkMemoryRequirements2;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import rltx.scene.GeometryBuffer;
import rltx.scene.StaticScene;

/**
 * The rasterising backend — "Normal" mode. A second {@link Renderer} beside the ray tracer
 * ({@link RtRenderer}) that fills the same externally-shared output image the GL compositor
 * blits, so the plugin drives it through the identical seam. See {@code docs/normal-mode.md}.
 *
 * <p><b>Increment 1 (this file) is the foundation only.</b> It stands up its own exported output
 * image and the Vk/GL semaphores, reuses {@link VkContext}, and each frame clears the viewport to
 * the scene's flat background colour — enough to prove a swapped-in backend reaches the screen
 * through the compositor with correct external-memory sharing and synchronisation. It does not
 * yet rasterise geometry, shadow, fog, or run the shared post chain; those are later increments,
 * and until then the geometry and environment uploads are accepted and ignored, and photo/readback
 * paths fail loudly rather than return a blank frame.
 *
 * <p>It reuses {@code VkContext} as-is, which today still requires ray-query device support, so
 * Normal mode runs on a ray-tracing GPU. Making those extensions optional (for non-RT hardware)
 * is a separate step tracked in the design doc.
 */
public final class NormalRenderer implements Renderer
{
	private static final int OUTPUT_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

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

	// The externally-shared image the compositor imports as a GL texture. Recreated on resize.
	private long image, memory, view, allocationSize, handle;
	private int outputWidth, outputHeight;

	// The clear colour for the next frame, taken from the flat background the front end sends.
	private float bgR, bgG, bgB;

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

	// ---- output sizing and the handles the GL compositor shares ----

	@Override
	public boolean ensureOutput(int width, int height, float scale, int dlss, boolean rayReconstruction)
	{
		if (image != 0 && outputWidth == width && outputHeight == height)
		{
			return false;
		}
		vkQueueWaitIdle(queue);
		destroyImage();
		createImage(width, height);
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
				.usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
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
			colorRange(viewInfo.subresourceRange());
			LongBuffer pView = stack.mallocLong(1);
			check(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView");
			view = pView.get(0);
		}
	}

	private void destroyImage()
	{
		if (image == 0)
		{
			return;
		}
		vkDestroyImageView(device, view, null);
		vkDestroyImage(device, image, null);
		vkFreeMemory(device, memory, null);
		image = memory = view = handle = allocationSize = 0;
	}

	@Override
	public long outputHandle()
	{
		return handle;
	}

	@Override
	public long outputAllocationSize()
	{
		return allocationSize;
	}

	@Override
	public long semaphoreVkDoneHandle()
	{
		return handleVkDone;
	}

	@Override
	public long semaphoreGlDoneHandle()
	{
		return handleGlDone;
	}

	@Override
	public int outputWidth()
	{
		return outputWidth;
	}

	@Override
	public int outputHeight()
	{
		return outputHeight;
	}

	@Override
	public int internalWidth()
	{
		return outputWidth;
	}

	@Override
	public int internalHeight()
	{
		return outputHeight;
	}

	// ---- drawing one frame ----

	@Override
	public void submit(FrameParams params, GeometryBuffer dynamic, GeometryBuffer translucent, GeometryBuffer water, boolean waitForGl, boolean signalGl)
	{
		bgR = params.backgroundR;
		bgG = params.backgroundG;
		bgB = params.backgroundB;
		waitPreviousFrame();
		try (MemoryStack stack = stackPush())
		{
			check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
				.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer");

			imageBarrier(stack, VK_IMAGE_LAYOUT_UNDEFINED,
				VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
				VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);

			VkClearColorValue clear = VkClearColorValue.calloc(stack);
			clear.float32(stack.floats(bgR, bgG, bgB, 1f));
			VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
			colorRange(range);
			vkCmdClearColorImage(cmd, image, VK_IMAGE_LAYOUT_GENERAL, clear, VkImageSubresourceRange.create(range.address(), 1));

			// The compositor samples the image after waiting on semaphoreVkDone; make the clear's
			// writes available to that read.
			imageBarrier(stack, VK_IMAGE_LAYOUT_GENERAL,
				VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
				VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT);

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
					.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT));
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

	private void imageBarrier(MemoryStack stack, int oldLayout, int srcStage, int srcAccess, int dstStage, int dstAccess)
	{
		VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
		barrier.get(0).sType$Default()
			.srcAccessMask(srcAccess)
			.dstAccessMask(dstAccess)
			.oldLayout(oldLayout)
			.newLayout(VK_IMAGE_LAYOUT_GENERAL)
			.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
			.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
			.image(image);
		colorRange(barrier.get(0).subresourceRange());
		vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
	}

	private static void colorRange(VkImageSubresourceRange range)
	{
		range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
			.baseMipLevel(0)
			.levelCount(1)
			.baseArrayLayer(0)
			.layerCount(1);
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

	@Override
	public long waitNanos()
	{
		return waitNanos;
	}

	@Override
	public double averageLogLuminance()
	{
		return Double.NaN;
	}

	@Override
	public double lastGpuMillis()
	{
		return 0.0;
	}

	@Override
	public String passReport()
	{
		return "normal: clear";
	}

	// ---- lifecycle ----

	@Override
	public void destroy()
	{
		vkQueueWaitIdle(queue);
		destroyImage();
		if (fence != 0)
		{
			vkDestroyFence(device, fence, null);
			fence = 0;
		}
		if (semaphoreVkDone != 0)
		{
			vkDestroySemaphore(device, semaphoreVkDone, null);
			semaphoreVkDone = 0;
		}
		if (semaphoreGlDone != 0)
		{
			vkDestroySemaphore(device, semaphoreGlDone, null);
			semaphoreGlDone = 0;
		}
	}

	// ---- geometry, materials and environment: accepted and ignored until the raster path lands ----

	@Override public void setStaticSet(int id, StaticScene scene, float[] transform) { }
	@Override public boolean updateZone(int id, int zx, int zz, StaticScene.Zone zone) { return true; }
	@Override public void removeStaticSet(int id) { }
	@Override public boolean hasStaticSet(int id) { return false; }
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
