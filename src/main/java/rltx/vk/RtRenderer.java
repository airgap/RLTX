package rltx.vk;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK13.*;
import static rltx.vk.VkUtil.alignUp;
import static rltx.vk.VkUtil.check;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageMemoryRequirementsInfo2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedRequirements;
import org.lwjgl.vulkan.VkMemoryRequirements2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTransformMatrixKHR;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;
import rltx.scene.GeometryBuffer;
import rltx.scene.StaticScene;
import rltx.scene.Materials;
import rltx.scene.WaterType;

/**
 * Owns every Vulkan object of the ray tracer: static and per-frame
 * acceleration structures, the ray-query compute pipeline, the temporal
 * history images, the exported output image and the two semaphores shared
 * with OpenGL.
 */
@Slf4j
public final class RtRenderer
{
	public static final int MAX_DYNAMIC_FACES = 1 << 19;
	private static final int MAX_INSTANCES = 8192;
	private static final int MAX_FACE_BASE = 1 << 23;
	private static final int DYNAMIC_INSTANCE_BIT = 1 << 23;
	// Instance masks matching trace.comp: rays that must see only solid surfaces use MASK_OPAQUE.
	private static final int MASK_OPAQUE = 0x1;
	private static final int MASK_TRANSLUCENT = 0x2;
	private static final int MASK_WATER = 0x4;
	/** Roofs the client hides: skipped by the rays the camera sees with, hit by the rays that light. */
	private static final int MASK_HIDDEN_ROOF = 0x8;
	private static final int BYTES_PER_FACE_POS = GeometryBuffer.FLOATS_PER_FACE * Float.BYTES;
	private static final int AS_OFFSET_ALIGNMENT = 256;
	private static final int OUTPUT_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
	private static final int HISTORY_COLOR_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
	private static final int HISTORY_POS_FORMAT = VK_FORMAT_R32G32B32A32_SFLOAT;
	private static final int FRAME_UBO_SIZE = 1024;

	private static final int FLAG_CULL = 1;
	private static final int FLAG_SHADOWS = 2;
	private static final int FLAG_PATTERN = 4;
	private static final int FLAG_RESET_HISTORY = 8;
	private static final int FLAG_TEXTURES = 16;
	private static final int FLAG_ANTIALIAS = 64;
	private static final int FLAG_WATER = 128;
	private static final int FLAG_PROCEDURAL_SKY = 256;
	private static final int FLAG_CLOUDS = 512;
	private static final int FLAG_CLOUD_SHADOWS = 1024;
	private static final int FLAG_CAUSTICS = 2048;
	private static final int FLAG_RAIN_RIPPLES = 4096;
	private static final int FLAG_PUDDLES = 8192;
	private static final int FLAG_TERRAIN_TEXTURES = 16384;
	private static final int FLAG_SMOOTH_TERRAIN = 32768;
	private static final int FLAG_RUNOFF = 65536;
	private static final int FLAG_MIST_EVERYWHERE = 131072;
	private static final int FLAG_FIREFLIES = 262144;
	private static final int FLAG_DUST = 524288;
	private static final int FLAG_STILL = 1048576;
	private static final int FLAG_THIN_LENS = 2097152;
	private static final int FLAG_PHYSICAL_SKY = 8388608;
	private static final int FLAG_WILDLIFE = 16777216;
	private static final int FLAG_UNDERWATER = 33554432;
	private static final int FLAG_SHIMMER = 67108864;
	private static final int FLAG_RAINBOWS = 134217728;
	private static final int FLAG_PEAKING = 268435456;
	private static final int FLAG_DISPLACEMENT = 536870912;
	private static final int FLAG_SKYBOX = 32;

	private static final int BINDING_TLAS = 0;
	private static final int BINDING_OUTPUT = 1;
	private static final int BINDING_STATIC_POS = 2;
	private static final int BINDING_STATIC_COL = 3;
	private static final int BINDING_DYNAMIC_POS = 4;
	private static final int BINDING_DYNAMIC_COL = 5;
	private static final int BINDING_FRAME = 6;
	private static final int BINDING_PREV_COLOR = 7;
	private static final int BINDING_CURR_COLOR = 8;
	private static final int BINDING_PREV_POS = 9;
	private static final int BINDING_CURR_POS = 10;
	private static final int BINDING_SAMPLE = 11;
	private static final int BINDING_SKYBOX = 12;
	private static final int BINDING_ALBEDO = 13;
	private static final int BINDING_NORMAL = 14;
	private static final int BINDING_PREV_MOMENTS = 15;
	private static final int BINDING_CURR_MOMENTS = 16;
	private static final int BINDING_FILTER_A = 17;
	private static final int BINDING_FILTER_B = 18;
	private static final int BINDING_STATIC_TEX = 19;
	private static final int BINDING_STATIC_UV = 20;
	private static final int BINDING_DYNAMIC_TEX = 21;
	private static final int BINDING_DYNAMIC_UV = 22;
	private static final int BINDING_TEXTURES = 23;
	private static final int BINDING_TEX_ANIM = 24;
	private static final int BINDING_WATER_TYPES = 25;
	private static final int BINDING_MIST = 26;
	private static final int BINDING_SHAFTS = 27;
	private static final int BINDING_BLOOM_SOURCE = 28;
	private static final int BINDING_BLOOM_A = 29;
	private static final int BINDING_BLOOM_B = 30;
	private static final int BINDING_EXPOSURE = 31;
	private static final int BINDING_LIGHTS = 32;
	private static final int BINDING_MATERIALS = 33;
	private static final int BINDING_DIFFUSE_A = 34;
	private static final int BINDING_DIFFUSE_B = 35;
	private static final int BINDING_HEIGHTS = 36;
	private static final int BINDING_RUNOFF = 37;
	private static final int BINDING_GUIDE = 38;
	private static final int BINDING_PLUMES = 39;
	private static final int BINDING_MARKERS = 40;
	private static final int BINDING_STARS = 41;
	private static final int BINDING_SKY_LUT = 42;
	private static final int BINDING_PRINTS = 43;
	private static final int BINDING_TREES = 44;
	private static final int BINDING_CELLS = 45;
	private static final int BINDING_COUNT = 46;
	private static final int HEIGHTS_MAX = 4 * 185 * 185;
	/** Local lights uploaded per frame, eight floats each. */
	public static final int MAX_LIGHTS = 256;
	/** Route entries uploaded per frame after the bounding box, four floats each, and the wisps that may follow them. */
	public static final int MAX_GUIDE_POINTS = 256;
	public static final int MAX_WISPS = 16;
	/** Smoke plumes marched per frame, four floats each. */
	public static final int MAX_PLUMES = 32;
	/** Ground marker tiles uploaded per frame after the bounding box, four floats each. */
	public static final int MAX_MARKERS = 256;
	/** Footprints kept, eight floats each. */
	public static final int MAX_PRINTS = 256;
	/** Trees shedding leaves per frame, four floats each. */
	public static final int MAX_TREES = 64;
	/** Occupancy layers of 64 by 64 cells, 128 words each: route, markers, footprints. */
	public static final int CELL_LAYERS = 3;
	public static final int CELL_WORDS = 128;
	private static final int MIST_GRID_MAX = 185 * 185 * 4;
	private static final int MAX_TEXTURES = 272;
	private static final int BYTES_PER_FACE_UV = GeometryBuffer.UV_FLOATS_PER_FACE * Float.BYTES;
	private static final int PUSH_CONSTANT_SIZE = 16;
	private static final int MAX_DENOISE_PASSES = 5;
	// Edge-stopping sharpness across face normals and along-surface distance, in pixel footprints.
	private static final float DENOISE_NORMAL_POWER = 32f;
	private static final float DENOISE_POSITION_SIGMA = 2f;

	private static final class Accel
	{
		final long handle;
		final long address;
		final VkBuf storage;
		final VkBuf scratch;

		Accel(long handle, long address, VkBuf storage, VkBuf scratch)
		{
			this.handle = handle;
			this.address = address;
			this.storage = storage;
			this.scratch = scratch;
		}
	}

	private static final class Img
	{
		long image;
		long memory;
		long view;
		long allocationSize;
		long handle;
	}

	private final VkContext ctx;
	private final VkDevice device;

	private long descriptorLayout;
	private long descriptorPool;
	private final long[] descriptorSets = new long[2];
	private long pipelineLayout;
	private long tracePipeline;
	private long resolvePipeline;
	private long atrousPipeline;
	private long postPipeline;
	private long bloomPipeline;
	private long exposurePipeline;
	private long shaftsPipeline;

	private final VkCommandBuffer cmd;
	private long fence;
	private boolean fencePending;
	private long timestampPool;
	private double lastGpuMillis;
	/** GPU time of each stage of the last finished frame, in the order of PASS_NAMES, milliseconds. */
	private static final String[] PASS_NAMES = {"build", "trace", "shafts", "denoise", "bloom", "post", "meter"};
	private static final int STAMPS = PASS_NAMES.length + 1;
	private final double[] passMillis = new double[PASS_NAMES.length];
	private long semaphoreVkDone;
	private long semaphoreGlDone;
	private long handleVkDone;
	private long handleGlDone;

	private VkBuf frameUbo;

	private VkBuf dynamicStagingPos;
	private VkBuf dynamicStagingCol;
	private VkBuf dynamicStagingTex;
	private VkBuf dynamicStagingUv;
	private VkBuf dynamicPos;
	private VkBuf dynamicCol;
	private VkBuf dynamicTex;
	private VkBuf dynamicUv;
	private Accel dynamicBlas;
	private Accel dynamicTranslucentBlas;
	private Accel dynamicWaterBlas;

	private VkBuf instances;
	private Accel tlas;

	// Static geometry of every loaded scene lives in one face pool so the shader indexes a single
	// set of buffers; each zone owns a slot in it and its own acceleration structures.
	private static final int POOL_FACES = 3 << 20;
	private VkBuf staticPos;
	private VkBuf staticCol;
	private VkBuf staticTex;
	private VkBuf staticUv;
	private final List<int[]> poolFree = new ArrayList<>();
	private final Map<Integer, StaticSet> staticSets = new LinkedHashMap<>();

	private static final class ZoneRes
	{
		int faceBase;
		int capacity;
		VkBuf storage;
		long[] handles = new long[0];
		long[] addresses = new long[0];
		int[] level = new int[0];
		int[] roof = new int[0];
		int[] faceOffset = new int[0];
		boolean[] translucent = new boolean[0];
		boolean[] water = new boolean[0];
		boolean[] sway = new boolean[0];
	}

	private static final class StaticSet
	{
		final int id;
		int zonesX;
		int zonesZ;
		ZoneRes[] zones;
		/** Zones whose foliage is drawn swayed through the dynamic path this frame instead. */
		boolean[] swayed;
		/** Zones whose water is drawn displaced through the dynamic path this frame instead. */
		boolean[] displaced;
		float[] transform;
		int minLevel;
		int level;
		int maxLevel = 3;
		Set<Integer> hiddenRoofIds = Collections.emptySet();
		final boolean[] levelHasRoofs = new boolean[4];

		StaticSet(int id)
		{
			this.id = id;
		}
	}

	private Img output;
	private Img sample;
	private Img albedo;
	private Img shafts;
	private Img bloomSource;
	private final Img[] bloomBlur = new Img[2];
	private final Img[] diffuseBlur = new Img[2];
	private Img normal;
	private final Img[] moments = new Img[2];
	private final Img[] filter = new Img[2];
	private Img skybox;
	private Img starMap;
	private Img skyLut;
	private long skyboxSampler;
	private Img gameTextures;
	private long textureSampler;
	private VkBuf textureAnimation;
	private VkBuf waterTypes;
	private VkBuf mistGrid;
	private VkBuf exposureReadback;
	private VkBuf lights;
	private VkBuf guide;
	private VkBuf plumes;
	private VkBuf markers;
	private VkBuf prints;
	private VkBuf trees;
	private VkBuf cells;
	private VkBuf materials;
	private VkBuf terrainHeights;
	private VkBuf runoff;
	private double averageLogLuminance = Double.NaN;
	private final Img[] historyColor = new Img[2];
	private final Img[] historyPos = new Img[2];
	private int outputWidth;
	private int outputHeight;
	private boolean outputUninitialized;
	private int parity;

	private boolean hasHistory;
	private final float[] prevCamera = new float[4];
	private final float[] prevForward = new float[9];

	private int frameIndex;
	private boolean warnedDynamicOverflow;
	private float[] motionScratch = new float[0];
	private final java.util.Random shutterRandom = new java.util.Random();

	public RtRenderer(VkContext ctx)
	{
		this.ctx = ctx;
		this.device = ctx.device;
		this.cmd = ctx.allocateCommandBuffer();

		createSyncObjects();
		createPipeline();
		frameUbo = ctx.createBuffer(FRAME_UBO_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		createDynamicResources();
		createTopLevel();

		createSkyboxSampler();
		ByteBuffer white = MemoryUtil.memAlloc(4);
		white.put((byte) 0xff).put((byte) 0xff).put((byte) 0xff).put((byte) 0xff).flip();
		try
		{
			setSkybox(1, 1, white);
		}
		finally
		{
			MemoryUtil.memFree(white);
		}

		createTextureSampler();
		textureAnimation = ctx.createBuffer((long) MAX_TEXTURES * 2 * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		float[] waterTable = WaterType.table();
		waterTypes = ctx.createBuffer((long) waterTable.length * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		waterTypes.mapped.asFloatBuffer().put(waterTable);
		mistGrid = ctx.createBuffer((long) MIST_GRID_MAX * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		exposureReadback = ctx.createBuffer(16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		exposureReadback.mapped.asFloatBuffer().put(0, Float.NaN);
		lights = ctx.createBuffer((long) MAX_LIGHTS * 8 * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		guide = ctx.createBuffer((long) (MAX_GUIDE_POINTS + 1 + MAX_WISPS) * 4 * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		plumes = ctx.createBuffer((long) MAX_PLUMES * 4 * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		markers = ctx.createBuffer((long) (MAX_MARKERS + 1) * 4 * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		prints = ctx.createBuffer((long) MAX_PRINTS * 8 * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		trees = ctx.createBuffer((long) MAX_TREES * 4 * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		cells = ctx.createBuffer((long) CELL_LAYERS * CELL_WORDS * Integer.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		materials = ctx.createBuffer((long) Materials.TEXTURES * Materials.FLOATS * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		terrainHeights = ctx.createBuffer((long) HEIGHTS_MAX * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		runoff = ctx.createBuffer((long) 185 * 185 * 4 * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		for (long set : descriptorSets)
		{
			writeBufferDescriptor(set, BINDING_RUNOFF, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, runoff);
			writeBufferDescriptor(set, BINDING_HEIGHTS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, terrainHeights);
			writeBufferDescriptor(set, BINDING_MATERIALS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, materials);
			writeBufferDescriptor(set, BINDING_LIGHTS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, lights);
			writeBufferDescriptor(set, BINDING_GUIDE, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, guide);
			writeBufferDescriptor(set, BINDING_PLUMES, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, plumes);
			writeBufferDescriptor(set, BINDING_MARKERS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, markers);
			writeBufferDescriptor(set, BINDING_PRINTS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, prints);
			writeBufferDescriptor(set, BINDING_TREES, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, trees);
			writeBufferDescriptor(set, BINDING_CELLS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, cells);
			writeBufferDescriptor(set, BINDING_EXPOSURE, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, exposureReadback);
			writeBufferDescriptor(set, BINDING_TEX_ANIM, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, textureAnimation);
			writeBufferDescriptor(set, BINDING_WATER_TYPES, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, waterTypes);
			writeBufferDescriptor(set, BINDING_MIST, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, mistGrid);
		}
		ByteBuffer whiteTexel = MemoryUtil.memAlloc(4);
		whiteTexel.put((byte) 0xff).put((byte) 0xff).put((byte) 0xff).put((byte) 0xff).flip();
		try
		{
			setTextureArray(1, 1, whiteTexel);
		}
		finally
		{
			MemoryUtil.memFree(whiteTexel);
		}

		int plainUsage = VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
		staticPos = ctx.createBuffer((long) POOL_FACES * BYTES_PER_FACE_POS, geometryUsage(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		staticCol = ctx.createBuffer((long) POOL_FACES * Integer.BYTES, plainUsage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		staticTex = ctx.createBuffer((long) POOL_FACES * Integer.BYTES, plainUsage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		staticUv = ctx.createBuffer((long) POOL_FACES * BYTES_PER_FACE_UV, plainUsage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		poolFree.add(new int[]{0, POOL_FACES});

		for (long set : descriptorSets)
		{
			writeAccelDescriptor(set);
			writeBufferDescriptor(set, BINDING_STATIC_POS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, staticPos);
			writeBufferDescriptor(set, BINDING_STATIC_COL, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, staticCol);
			writeBufferDescriptor(set, BINDING_DYNAMIC_POS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, dynamicPos);
			writeBufferDescriptor(set, BINDING_DYNAMIC_COL, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, dynamicCol);
			writeBufferDescriptor(set, BINDING_STATIC_TEX, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, staticTex);
			writeBufferDescriptor(set, BINDING_STATIC_UV, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, staticUv);
			writeBufferDescriptor(set, BINDING_DYNAMIC_TEX, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, dynamicTex);
			writeBufferDescriptor(set, BINDING_DYNAMIC_UV, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, dynamicUv);
			writeBufferDescriptor(set, BINDING_FRAME, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, frameUbo);
		}
	}

	private void createTextureSampler()
	{
		try (MemoryStack stack = stackPush())
		{
			VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
				.magFilter(VK_FILTER_LINEAR)
				.minFilter(VK_FILTER_LINEAR)
				.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
				.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.maxLod(0f);
			LongBuffer pSampler = stack.mallocLong(1);
			check(vkCreateSampler(device, info, null, pSampler), "vkCreateSampler");
			textureSampler = pSampler.get(0);
		}
	}

	/** Per-texture UV scroll velocities, two floats each, in texture units per 128 client cycles. */
	public void setTextureAnimation(float[] uvPerCycle)
	{
		FloatBuffer dst = textureAnimation.mapped.asFloatBuffer();
		dst.put(uvPerCycle, 0, Math.min(uvPerCycle.length, MAX_TEXTURES * 2));
	}

	/**
	 * Replaces the game texture array: {@code layers} square textures of {@code size} pixels,
	 * tightly packed RGBA8 one after another. Blocks until the upload completes.
	 */
	public void setTextureArray(int layers, int size, ByteBuffer rgba)
	{
		idle();
		if (gameTextures != null)
		{
			destroyImage(gameTextures);
			gameTextures = null;
		}
		gameTextures = createImage(size, size, layers, VK_FORMAT_R8G8B8A8_UNORM,
			VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, false);

		long bytes = (long) layers * size * size * 4;
		VkBuf staging = ctx.createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		MemoryUtil.memCopy(MemoryUtil.memAddress(rgba), MemoryUtil.memAddress(staging.mapped), bytes);

		VkCommandBuffer upload = ctx.beginOneTime();
		try (MemoryStack stack = stackPush())
		{
			imageLayout(upload, gameTextures.image, layers, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
			region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(layers);
			region.get(0).imageExtent().width(size).height(size).depth(1);
			vkCmdCopyBufferToImage(upload, staging.buffer, gameTextures.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
			imageLayout(upload, gameTextures.image, layers, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
				VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
		}
		ctx.endOneTimeAndWait(upload);
		ctx.destroyBuffer(staging);

		try (MemoryStack stack = stackPush())
		{
			VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
			info.get(0).sampler(textureSampler).imageView(gameTextures.view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			for (long set : descriptorSets)
			{
				VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
				write.get(0).sType$Default()
					.dstSet(set)
					.dstBinding(BINDING_TEXTURES)
					.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
					.descriptorCount(1)
					.pImageInfo(info);
				vkUpdateDescriptorSets(device, write, null);
			}
		}
		log.info("Game textures: {} layers of {}x{}", layers, size, size);
	}

	public long semaphoreVkDoneHandle()
	{
		return handleVkDone;
	}

	public long semaphoreGlDoneHandle()
	{
		return handleGlDone;
	}

	public long outputHandle()
	{
		return output.handle;
	}

	public long outputAllocationSize()
	{
		return output.allocationSize;
	}

	private void createSyncObjects()
	{
		try (MemoryStack stack = stackPush())
		{
			VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
			LongBuffer pFence = stack.mallocLong(1);
			check(vkCreateFence(device, fenceInfo, null, pFence), "vkCreateFence");
			fence = pFence.get(0);

			VkQueryPoolCreateInfo queryInfo = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
				.queryType(VK_QUERY_TYPE_TIMESTAMP)
				.queryCount(STAMPS);
			LongBuffer pPool = stack.mallocLong(1);
			check(vkCreateQueryPool(device, queryInfo, null, pPool), "vkCreateQueryPool");
			timestampPool = pPool.get(0);

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

	private void createPipeline()
	{
		try (MemoryStack stack = stackPush())
		{
			int[] types = new int[BINDING_COUNT];
			types[BINDING_TLAS] = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
			types[BINDING_OUTPUT] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_STATIC_POS] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_STATIC_COL] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_DYNAMIC_POS] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_DYNAMIC_COL] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_FRAME] = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
			types[BINDING_PREV_COLOR] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_CURR_COLOR] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_PREV_POS] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_CURR_POS] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_SAMPLE] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_SKYBOX] = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
			types[BINDING_ALBEDO] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_NORMAL] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_PREV_MOMENTS] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_CURR_MOMENTS] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_FILTER_A] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_FILTER_B] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_STATIC_TEX] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_STATIC_UV] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_DYNAMIC_TEX] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_DYNAMIC_UV] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_TEXTURES] = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
			types[BINDING_TEX_ANIM] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_WATER_TYPES] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_MIST] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_SHAFTS] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_BLOOM_SOURCE] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_BLOOM_A] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_BLOOM_B] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_EXPOSURE] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_LIGHTS] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_MATERIALS] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_DIFFUSE_A] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_DIFFUSE_B] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			types[BINDING_HEIGHTS] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_RUNOFF] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_GUIDE] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_PLUMES] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_MARKERS] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_PRINTS] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_TREES] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_CELLS] = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			types[BINDING_STARS] = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
			types[BINDING_SKY_LUT] = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;

			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
			for (int i = 0; i < BINDING_COUNT; ++i)
			{
				bindings.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			}
			VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
			LongBuffer pLayout = stack.mallocLong(1);
			check(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout), "vkCreateDescriptorSetLayout");
			descriptorLayout = pLayout.get(0);

			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(5, stack);
			sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(2);
			sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(36);
			sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(48);
			sizes.get(3).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(2);
			sizes.get(4).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(12);
			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(2).pPoolSizes(sizes);
			LongBuffer pPool = stack.mallocLong(1);
			check(vkCreateDescriptorPool(device, poolInfo, null, pPool), "vkCreateDescriptorPool");
			descriptorPool = pPool.get(0);

			VkDescriptorSetAllocateInfo setInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
				.descriptorPool(descriptorPool)
				.pSetLayouts(stack.longs(descriptorLayout, descriptorLayout));
			LongBuffer pSets = stack.mallocLong(2);
			check(vkAllocateDescriptorSets(device, setInfo, pSets), "vkAllocateDescriptorSets");
			descriptorSets[0] = pSets.get(0);
			descriptorSets[1] = pSets.get(1);

			VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
			pushRange.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_CONSTANT_SIZE);
			VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
				.pSetLayouts(stack.longs(descriptorLayout))
				.pPushConstantRanges(pushRange);
			LongBuffer pPipelineLayout = stack.mallocLong(1);
			check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout), "vkCreatePipelineLayout");
			pipelineLayout = pPipelineLayout.get(0);

		}
		tracePipeline = createComputePipeline("/rltx/trace.comp.spv");
		resolvePipeline = createComputePipeline("/rltx/resolve.comp.spv");
		atrousPipeline = createComputePipeline("/rltx/atrous.comp.spv");
		postPipeline = createComputePipeline("/rltx/post.comp.spv");
		bloomPipeline = createComputePipeline("/rltx/bloom.comp.spv");
		exposurePipeline = createComputePipeline("/rltx/exposure.comp.spv");
		shaftsPipeline = createComputePipeline("/rltx/shafts.comp.spv");
	}

	private long createComputePipeline(String resource)
	{
		ByteBuffer code = loadShader(resource);
		try (MemoryStack stack = stackPush())
		{
			VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
			LongBuffer pModule = stack.mallocLong(1);
			check(vkCreateShaderModule(device, moduleInfo, null, pModule), "vkCreateShaderModule");
			long module = pModule.get(0);

			VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
				.stage(VK_SHADER_STAGE_COMPUTE_BIT)
				.module(module)
				.pName(stack.UTF8("main"));
			VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
			pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
			LongBuffer pPipeline = stack.mallocLong(1);
			check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline), "vkCreateComputePipelines");
			vkDestroyShaderModule(device, module, null);
			return pPipeline.get(0);
		}
		finally
		{
			MemoryUtil.memFree(code);
		}
	}

	private static ByteBuffer loadShader(String resource)
	{
		try (InputStream in = RtRenderer.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing shader resource " + resource);
			}
			byte[] bytes = in.readAllBytes();
			ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
			buf.put(bytes).flip();
			return buf;
		}
		catch (IOException e)
		{
			throw new IllegalStateException("Failed to read " + resource, e);
		}
	}

	private void createDynamicResources()
	{
		long posBytes = (long) MAX_DYNAMIC_FACES * BYTES_PER_FACE_POS;
		long colBytes = (long) MAX_DYNAMIC_FACES * Integer.BYTES;
		int hostFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
		dynamicStagingPos = ctx.createBuffer(posBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostFlags);
		dynamicStagingCol = ctx.createBuffer(colBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostFlags);
		dynamicPos = ctx.createBuffer(posBytes, geometryUsage(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		dynamicCol = ctx.createBuffer(colBytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		long uvBytes = (long) MAX_DYNAMIC_FACES * BYTES_PER_FACE_UV;
		dynamicStagingTex = ctx.createBuffer(colBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostFlags);
		dynamicStagingUv = ctx.createBuffer(uvBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostFlags);
		dynamicTex = ctx.createBuffer(colBytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		dynamicUv = ctx.createBuffer(uvBytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

		dynamicBlas = createDynamicBlas(true);
		dynamicTranslucentBlas = createDynamicBlas(false);
		dynamicWaterBlas = createDynamicBlas(true);
	}

	// Both dynamic structures are sized for the whole buffer; each frame builds them over its
	// own face range (opaque faces first, translucent after) with the actual counts.
	private Accel createDynamicBlas(boolean opaque)
	{
		try (MemoryStack stack = stackPush())
		{
			VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			fillTriangles(geometry.get(0), dynamicPos.address, MAX_DYNAMIC_FACES, opaque);
			VkAccelerationStructureBuildGeometryInfoKHR info = buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
				VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR, geometry);
			VkAccelerationStructureBuildSizesInfoKHR sizes = querySizes(stack, info, MAX_DYNAMIC_FACES);
			VkBuf storage = ctx.createBuffer(sizes.accelerationStructureSize(), accelStorageUsage(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
			long handle = createAccel(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, storage.buffer, 0, sizes.accelerationStructureSize());
			VkBuf scratch = createScratch(sizes.buildScratchSize());
			return new Accel(handle, accelAddress(handle), storage, scratch);
		}
	}

	private void createTopLevel()
	{
		instances = ctx.createBuffer((long) MAX_INSTANCES * VkAccelerationStructureInstanceKHR.SIZEOF,
			VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		try (MemoryStack stack = stackPush())
		{
			VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			fillInstances(geometry.get(0), instances.address);
			VkAccelerationStructureBuildGeometryInfoKHR info = buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
				VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR, geometry);
			VkAccelerationStructureBuildSizesInfoKHR sizes = querySizes(stack, info, MAX_INSTANCES);
			VkBuf storage = ctx.createBuffer(sizes.accelerationStructureSize(), accelStorageUsage(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
			long handle = createAccel(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, storage.buffer, 0, sizes.accelerationStructureSize());
			VkBuf scratch = createScratch(sizes.buildScratchSize());
			tlas = new Accel(handle, accelAddress(handle), storage, scratch);
		}
	}

	private static int geometryUsage()
	{
		return VK_BUFFER_USAGE_TRANSFER_DST_BIT
			| VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
			| VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
			| VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
	}

	private static int accelStorageUsage()
	{
		return VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
	}

	private VkBuf createScratch(long size)
	{
		return ctx.createBuffer(size + ctx.scratchAlignment,
			VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
			VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
	}

	private long scratchAddress(VkBuf scratch)
	{
		return alignUp(scratch.address, ctx.scratchAlignment);
	}

	private static void fillTriangles(VkAccelerationStructureGeometryKHR geometry, long vertexAddress, int maxTriangles, boolean opaque)
	{
		geometry.sType$Default()
			.geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
			.flags(opaque ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
		geometry.geometry().triangles().sType$Default()
			.vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
			.vertexStride(3 * Float.BYTES)
			.maxVertex(maxTriangles * 3 - 1)
			.indexType(VK_INDEX_TYPE_NONE_KHR);
		geometry.geometry().triangles().vertexData().deviceAddress(vertexAddress);
	}

	private static void fillInstances(VkAccelerationStructureGeometryKHR geometry, long instanceAddress)
	{
		geometry.sType$Default()
			.geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
			.flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
		geometry.geometry().instances().sType$Default().arrayOfPointers(false);
		geometry.geometry().instances().data().deviceAddress(instanceAddress);
	}

	private static VkAccelerationStructureBuildGeometryInfoKHR buildInfo(MemoryStack stack, int type, int flags,
		VkAccelerationStructureGeometryKHR.Buffer geometry)
	{
		return VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default()
			.type(type)
			.flags(flags)
			.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
			.pGeometries(geometry)
			.geometryCount(1);
	}

	private VkAccelerationStructureBuildSizesInfoKHR querySizes(MemoryStack stack, VkAccelerationStructureBuildGeometryInfoKHR info, int maxPrimitives)
	{
		VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
		vkGetAccelerationStructureBuildSizesKHR(device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, stack.ints(maxPrimitives), sizes);
		return sizes;
	}

	private long createAccel(int type, long buffer, long offset, long size)
	{
		try (MemoryStack stack = stackPush())
		{
			VkAccelerationStructureCreateInfoKHR info = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
				.buffer(buffer)
				.offset(offset)
				.size(size)
				.type(type);
			LongBuffer pAccel = stack.mallocLong(1);
			check(vkCreateAccelerationStructureKHR(device, info, null, pAccel), "vkCreateAccelerationStructureKHR");
			return pAccel.get(0);
		}
	}

	private long accelAddress(long handle)
	{
		try (MemoryStack stack = stackPush())
		{
			VkAccelerationStructureDeviceAddressInfoKHR info = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default()
				.accelerationStructure(handle);
			return vkGetAccelerationStructureDeviceAddressKHR(device, info);
		}
	}

	private void recordBuild(VkCommandBuffer commandBuffer, VkAccelerationStructureBuildGeometryInfoKHR info, long dst, long scratch, int primitiveCount)
	{
		try (MemoryStack stack = stackPush())
		{
			info.dstAccelerationStructure(dst);
			info.scratchData().deviceAddress(scratch);
			VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
			range.get(0).primitiveCount(primitiveCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
			VkAccelerationStructureBuildGeometryInfoKHR.Buffer infos = VkAccelerationStructureBuildGeometryInfoKHR.create(info.address(), 1);
			vkCmdBuildAccelerationStructuresKHR(commandBuffer, infos, stack.pointers(range.address()));
		}
	}

	private void memoryBarrier(VkCommandBuffer commandBuffer, int srcStage, int srcAccess, int dstStage, int dstAccess)
	{
		try (MemoryStack stack = stackPush())
		{
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
			barrier.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
			vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, barrier, null, null);
		}
	}

	private void writeAccelDescriptor(long set)
	{
		try (MemoryStack stack = stackPush())
		{
			VkWriteDescriptorSetAccelerationStructureKHR accelWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default()
				.pAccelerationStructures(stack.longs(tlas.handle));
			VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
			write.get(0).sType$Default()
				.pNext(accelWrite.address())
				.dstSet(set)
				.dstBinding(BINDING_TLAS)
				.descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
				.descriptorCount(1);
			vkUpdateDescriptorSets(device, write, null);
		}
	}

	private void writeBufferDescriptor(long set, int binding, int type, VkBuf buf)
	{
		try (MemoryStack stack = stackPush())
		{
			VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
			info.get(0).buffer(buf.buffer).offset(0).range(VK_WHOLE_SIZE);
			VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
			write.get(0).sType$Default()
				.dstSet(set)
				.dstBinding(binding)
				.descriptorType(type)
				.descriptorCount(1)
				.pBufferInfo(info);
			vkUpdateDescriptorSets(device, write, null);
		}
	}

	private void writeImageDescriptor(long set, int binding, long view)
	{
		try (MemoryStack stack = stackPush())
		{
			VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
			info.get(0).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
			VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
			write.get(0).sType$Default()
				.dstSet(set)
				.dstBinding(binding)
				.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.descriptorCount(1)
				.pImageInfo(info);
			vkUpdateDescriptorSets(device, write, null);
		}
	}

	private void createSkyboxSampler()
	{
		try (MemoryStack stack = stackPush())
		{
			VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
				.magFilter(VK_FILTER_LINEAR)
				.minFilter(VK_FILTER_LINEAR)
				.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
				.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.maxLod(0f);
			LongBuffer pSampler = stack.mallocLong(1);
			check(vkCreateSampler(device, info, null, pSampler), "vkCreateSampler");
			skyboxSampler = pSampler.get(0);
		}
	}

	/**
	 * Replaces the equirectangular skybox texture. Blocks until the upload completes.
	 *
	 * @param rgba tightly packed RGBA8 rows, {@code width * height * 4} bytes from its position
	 */
	public void setSkybox(int width, int height, ByteBuffer rgba)
	{
		skybox = replaceSampled(skybox, width, height, rgba, BINDING_SKYBOX, VK_FORMAT_R8G8B8A8_UNORM, 4);
	}

	/** Replaces the star map, equirectangular in equatorial coordinates. Blocks until the upload completes. */
	public void setStarMap(int width, int height, ByteBuffer rgba)
	{
		starMap = replaceSampled(starMap, width, height, rgba, BINDING_STARS, VK_FORMAT_R8G8B8A8_UNORM, 4);
	}

	/** Replaces the scattered-light sky map, RGBA floats over scene azimuth and elevation. Blocks until the upload completes. */
	public void setAtmosphere(int width, int height, ByteBuffer rgba)
	{
		skyLut = replaceSampled(skyLut, width, height, rgba, BINDING_SKY_LUT, VK_FORMAT_R32G32B32A32_SFLOAT, 16);
	}

	private Img replaceSampled(Img old, int width, int height, ByteBuffer rgba, int binding, int format, int bytesPerPixel)
	{
		idle();
		if (old != null)
		{
			destroyImage(old);
		}
		Img skybox = createImage(width, height, format,
			VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, false);

		long bytes = (long) width * height * bytesPerPixel;
		VkBuf staging = ctx.createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		MemoryUtil.memCopy(MemoryUtil.memAddress(rgba), MemoryUtil.memAddress(staging.mapped), bytes);

		VkCommandBuffer upload = ctx.beginOneTime();
		try (MemoryStack stack = stackPush())
		{
			imageLayout(upload, skybox.image, 1, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
			region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.get(0).imageExtent().width(width).height(height).depth(1);
			vkCmdCopyBufferToImage(upload, staging.buffer, skybox.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
			imageLayout(upload, skybox.image, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
				VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
		}
		ctx.endOneTimeAndWait(upload);
		ctx.destroyBuffer(staging);

		try (MemoryStack stack = stackPush())
		{
			VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
			info.get(0).sampler(skyboxSampler).imageView(skybox.view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			for (long set : descriptorSets)
			{
				VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
				write.get(0).sType$Default()
					.dstSet(set)
					.dstBinding(binding)
					.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
					.descriptorCount(1)
					.pImageInfo(info);
				vkUpdateDescriptorSets(device, write, null);
			}
		}
		return skybox;
	}

	private void imageLayout(VkCommandBuffer commandBuffer, long image, int layers, int oldLayout, int newLayout,
		int srcStage, int srcAccess, int dstStage, int dstAccess)
	{
		try (MemoryStack stack = stackPush())
		{
			VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
			barrier.get(0).sType$Default()
				.srcAccessMask(srcAccess)
				.dstAccessMask(dstAccess)
				.oldLayout(oldLayout)
				.newLayout(newLayout)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.image(image);
			fillColorRange(barrier.get(0).subresourceRange());
			barrier.get(0).subresourceRange().layerCount(layers);
			vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, null, null, barrier);
		}
	}

	// ---- static geometry: pool slots, zones and their acceleration structures ----

	private int allocateFaces(int faces)
	{
		for (int i = 0; i < poolFree.size(); ++i)
		{
			int[] range = poolFree.get(i);
			if (range[1] >= faces)
			{
				int start = range[0];
				range[0] += faces;
				range[1] -= faces;
				if (range[1] == 0)
				{
					poolFree.remove(i);
				}
				return start;
			}
		}
		return -1;
	}

	private void freeFaces(int start, int length)
	{
		poolFree.add(new int[]{start, length});
		poolFree.sort((a, b) -> Integer.compare(a[0], b[0]));
		for (int i = 0; i + 1 < poolFree.size(); )
		{
			int[] a = poolFree.get(i);
			int[] b = poolFree.get(i + 1);
			if (a[0] + a[1] == b[0])
			{
				a[1] += b[1];
				poolFree.remove(i + 1);
			}
			else
			{
				++i;
			}
		}
	}

	// Slack so a zone can gain a few objects (an opened door, a spawned object) without a
	// whole-scene rebuild.
	private static int slotCapacity(int faces)
	{
		return faces + faces / 4 + 32;
	}

	/**
	 * Replaces the static geometry of one scene (the top-level scene or a nested world view).
	 * Blocks until every zone's acceleration structures are built.
	 */
	public void setStaticSet(int id, StaticScene scene, float[] transform)
	{
		idle();
		removeStaticSet(id);
		StaticSet set = new StaticSet(id);
		set.transform = transform;
		set.zonesX = scene.zonesX;
		set.zonesZ = scene.zonesZ;
		set.zones = new ZoneRes[scene.zones.length];

		int totalFaces = 0;
		long maxScratch = 0;
		for (int i = 0; i < scene.zones.length; ++i)
		{
			StaticScene.Zone zone = scene.zones[i];
			if (zone == null)
			{
				continue;
			}
			ZoneRes res = new ZoneRes();
			res.capacity = slotCapacity(zone.geometry.faces());
			res.faceBase = allocateFaces(res.capacity);
			if (res.faceBase < 0)
			{
				log.warn("Static face pool exhausted; zone {},{} of scene {} dropped", zone.zx, zone.zz, id);
				continue;
			}
			set.zones[i] = res;
			totalFaces += zone.geometry.faces();
			maxScratch = Math.max(maxScratch, prepareZoneAccel(res, zone));
		}

		VkBuf staging = createStaging(totalFaces);
		VkBuf scratch = createScratch(Math.max(maxScratch, 1));
		VkCommandBuffer upload = ctx.beginOneTime();
		int stagingFace = 0;
		for (int i = 0; i < scene.zones.length; ++i)
		{
			StaticScene.Zone zone = scene.zones[i];
			ZoneRes res = set.zones[i];
			if (zone == null || res == null)
			{
				continue;
			}
			stageZone(upload, staging, stagingFace, zone.geometry, res.faceBase);
			stagingFace += zone.geometry.faces();
		}
		memoryBarrier(upload,
			VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
			VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
			VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_SHADER_READ_BIT);
		for (int i = 0; i < scene.zones.length; ++i)
		{
			if (scene.zones[i] != null && set.zones[i] != null)
			{
				buildZoneAccel(upload, set.zones[i], scene.zones[i], scratch);
			}
		}
		ctx.endOneTimeAndWait(upload);
		ctx.destroyBuffer(staging);
		ctx.destroyBuffer(scratch);

		refreshRoofFlags(set);
		staticSets.put(id, set);
		log.info("Static scene {}: {} faces in {} zones", id, totalFaces, scene.zones.length);
	}

	/**
	 * Replaces one zone of a loaded scene in place. Returns false when the zone no longer fits
	 * its slot, in which case the caller should rebuild the whole scene.
	 */
	public boolean updateZone(int id, int zx, int zz, StaticScene.Zone zone)
	{
		StaticSet set = staticSets.get(id);
		if (set == null)
		{
			return false;
		}
		if (zx >= set.zonesX || zz >= set.zonesZ)
		{
			// Nothing of that zone was ever uploaded, so there is nothing to replace.
			return true;
		}
		int index = zx * set.zonesZ + zz;
		ZoneRes res = set.zones[index];
		if (zone == null)
		{
			if (res != null)
			{
				idle();
				destroyZoneAccel(res);
				refreshRoofFlags(set);
			}
			return true;
		}
		if (res == null)
		{
			res = new ZoneRes();
			res.capacity = slotCapacity(zone.geometry.faces());
			res.faceBase = allocateFaces(res.capacity);
			if (res.faceBase < 0)
			{
				return false;
			}
			set.zones[index] = res;
		}
		else if (zone.geometry.faces() > res.capacity)
		{
			return false;
		}

		idle();
		destroyZoneAccel(res);
		long scratchSize = prepareZoneAccel(res, zone);
		VkBuf staging = createStaging(zone.geometry.faces());
		VkBuf scratch = createScratch(Math.max(scratchSize, 1));
		VkCommandBuffer upload = ctx.beginOneTime();
		stageZone(upload, staging, 0, zone.geometry, res.faceBase);
		memoryBarrier(upload,
			VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
			VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
			VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_SHADER_READ_BIT);
		buildZoneAccel(upload, res, zone, scratch);
		ctx.endOneTimeAndWait(upload);
		ctx.destroyBuffer(staging);
		ctx.destroyBuffer(scratch);
		refreshRoofFlags(set);
		return true;
	}

	public void removeStaticSet(int id)
	{
		StaticSet set = staticSets.remove(id);
		if (set == null)
		{
			return;
		}
		idle();
		for (ZoneRes res : set.zones)
		{
			if (res != null)
			{
				destroyZoneAccel(res);
				freeFaces(res.faceBase, res.capacity);
			}
		}
	}

	/** Ground heights and mist coverage of the top-level scene, as built by StaticSceneBuilder.mistGrid. */
	public void setMistGrid(float[] grid)
	{
		idle();
		mistGrid.mapped.asFloatBuffer().put(grid, 0, Math.min(grid.length, MIST_GRID_MAX));
	}

	/**
	 * Local lights for the coming frame: position xyz and radius, then premultiplied colour and
	 * a spare float, per light. Called between beginFrame and submit, once the previous frame's
	 * reads of the buffer have finished.
	 */
	public void setLights(float[] packed, int count)
	{
		lights.mapped.asFloatBuffer().put(packed, 0, Math.min(count, MAX_LIGHTS) * 8);
	}

	/**
	 * The route to glow for the coming frame: its bounding box, then tile centres with their
	 * distance along the route, four floats each. Written between beginFrame and submit.
	 */
	public void setGuide(float[] packed, int floats)
	{
		guide.mapped.asFloatBuffer().put(packed, 0, Math.min(floats, (MAX_GUIDE_POINTS + 1 + MAX_WISPS) * 4));
	}

	/** Smoke sources for the coming frame, nearest first: x, y, z and strength each. Written between beginFrame and submit. */
	public void setPlumes(float[] packed, int floats)
	{
		plumes.mapped.asFloatBuffer().put(packed, 0, Math.min(floats, MAX_PLUMES * 4));
	}

	/** Occupancy bits for the coming frame, CELL_LAYERS by CELL_WORDS words. Written between beginFrame and submit. */
	public void setCells(int[] bits)
	{
		cells.mapped.asIntBuffer().put(bits, 0, CELL_LAYERS * CELL_WORDS);
	}

	/** Trees shedding leaves this frame, nearest first: x, crown height, z and crown radius each. Written between beginFrame and submit. */
	public void setTrees(float[] packed, int floats)
	{
		trees.mapped.asFloatBuffer().put(packed, 0, Math.min(floats, MAX_TREES * 4));
	}

	/** Footprints for the coming frame: position and time, then heading, side and a spare, per print. Written between beginFrame and submit. */
	public void setPrints(float[] packed, int floats)
	{
		prints.mapped.asFloatBuffer().put(packed, 0, Math.min(floats, MAX_PRINTS * 8));
	}

	/** Ground marker tiles for the coming frame: a bounding box, then tile centres with the colour's bits in w. Written between beginFrame and submit. */
	public void setMarkers(float[] packed, int floats)
	{
		markers.mapped.asFloatBuffer().put(packed, 0, Math.min(floats, (MAX_MARKERS + 1) * 4));
	}

	/** Water depth and flow per ground vertex from the runoff simulation; written between beginFrame and submit. */
	public void setRunoff(float[] packed)
	{
		runoff.mapped.asFloatBuffer().put(packed, 0, Math.min(packed.length, 185 * 185 * 4));
	}

	/** Tile heights of the top-level scene, plane-major then x then y, for smooth terrain normals. */
	public void setTerrainHeights(float[] heights)
	{
		idle();
		terrainHeights.mapped.asFloatBuffer().put(heights, 0, Math.min(heights.length, HEIGHTS_MAX));
	}

	/** Surface properties per vanilla texture id, as packed by Materials.table(). */
	public void setMaterials(float[] table)
	{
		idle();
		materials.mapped.asFloatBuffer().put(table, 0, Math.min(table.length, Materials.TEXTURES * Materials.FLOATS));
	}

	/** Which zones of a scene have their foliage groups replaced by swayed dynamic copies this frame. */
	public void setSwayedZones(int id, boolean[] swayed)
	{
		StaticSet set = staticSets.get(id);
		if (set != null)
		{
			set.swayed = swayed;
		}
	}

	/** Which zones of a scene have their water groups replaced by displaced dynamic copies this frame. */
	public void setDisplacedZones(int id, boolean[] displaced)
	{
		StaticSet set = staticSets.get(id);
		if (set != null)
		{
			set.displaced = displaced;
		}
	}

	/** Per-frame placement and visibility of a loaded scene. */
	public void setStaticView(int id, float[] transform, int minLevel, int level, int maxLevel, Set<Integer> hiddenRoofIds)
	{
		StaticSet set = staticSets.get(id);
		if (set == null)
		{
			return;
		}
		set.transform = transform;
		set.minLevel = minLevel;
		set.level = level;
		set.maxLevel = maxLevel;
		set.hiddenRoofIds = hiddenRoofIds;
	}

	public boolean hasStaticSet(int id)
	{
		return staticSets.containsKey(id);
	}

	private VkBuf createStaging(int faces)
	{
		int n = Math.max(faces, 1);
		return ctx.createBuffer((long) n * (BYTES_PER_FACE_POS + Integer.BYTES + Integer.BYTES + BYTES_PER_FACE_UV),
			VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
	}

	// The staging buffer holds each zone's four attribute streams back to back; each is copied
	// into its pool buffer at the zone's slot.
	private void stageZone(VkCommandBuffer upload, VkBuf staging, int stagingFace, GeometryBuffer geometry, int poolFace)
	{
		int faces = geometry.faces();
		long posBytes = (long) faces * BYTES_PER_FACE_POS;
		long colBytes = (long) faces * Integer.BYTES;
		long uvBytes = (long) faces * BYTES_PER_FACE_UV;
		long base = (long) stagingFace * (BYTES_PER_FACE_POS + Integer.BYTES + Integer.BYTES + BYTES_PER_FACE_UV);
		ByteBuffer m = staging.mapped;
		m.position((int) base);
		m.asFloatBuffer().put(geometry.positions(), 0, faces * GeometryBuffer.FLOATS_PER_FACE);
		m.position((int) (base + posBytes));
		m.asIntBuffer().put(geometry.colors(), 0, faces);
		m.position((int) (base + posBytes + colBytes));
		m.asIntBuffer().put(geometry.textures(), 0, faces);
		m.position((int) (base + posBytes + 2 * colBytes));
		m.asFloatBuffer().put(geometry.uvs(), 0, faces * GeometryBuffer.UV_FLOATS_PER_FACE);
		m.position(0);

		try (MemoryStack stack = stackPush())
		{
			VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
			copy.get(0).srcOffset(base).dstOffset((long) poolFace * BYTES_PER_FACE_POS).size(posBytes);
			vkCmdCopyBuffer(upload, staging.buffer, staticPos.buffer, copy);
			copy.get(0).srcOffset(base + posBytes).dstOffset((long) poolFace * Integer.BYTES).size(colBytes);
			vkCmdCopyBuffer(upload, staging.buffer, staticCol.buffer, copy);
			copy.get(0).srcOffset(base + posBytes + colBytes).dstOffset((long) poolFace * Integer.BYTES).size(colBytes);
			vkCmdCopyBuffer(upload, staging.buffer, staticTex.buffer, copy);
			copy.get(0).srcOffset(base + posBytes + 2 * colBytes).dstOffset((long) poolFace * BYTES_PER_FACE_UV).size(uvBytes);
			vkCmdCopyBuffer(upload, staging.buffer, staticUv.buffer, copy);
		}
	}

	// Sizes and creates the acceleration structures of a zone's groups; returns the scratch
	// size the largest build needs.
	private long prepareZoneAccel(ZoneRes res, StaticScene.Zone zone)
	{
		int groups = zone.groupCount();
		long[] size = new long[groups];
		long[] offset = new long[groups];
		long total = 0;
		long maxScratch = 0;
		for (int g = 0; g < groups; ++g)
		{
			try (MemoryStack stack = stackPush())
			{
				VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
				fillTriangles(geom.get(0), zoneVertexAddress(res, zone, g), zone.groupFaceCount[g], !zone.groupTranslucent[g]);
				VkAccelerationStructureBuildGeometryInfoKHR info = buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
					VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR, geom);
				VkAccelerationStructureBuildSizesInfoKHR sizes = querySizes(stack, info, zone.groupFaceCount[g]);
				size[g] = sizes.accelerationStructureSize();
				offset[g] = total;
				total += alignUp(size[g], AS_OFFSET_ALIGNMENT);
				maxScratch = Math.max(maxScratch, sizes.buildScratchSize());
			}
		}
		res.storage = ctx.createBuffer(Math.max(total, AS_OFFSET_ALIGNMENT), accelStorageUsage(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		res.handles = new long[groups];
		res.addresses = new long[groups];
		res.level = zone.groupLevel.clone();
		res.roof = zone.groupRoofId.clone();
		res.faceOffset = zone.groupFaceBase.clone();
		res.translucent = zone.groupTranslucent.clone();
		res.water = zone.groupWater.clone();
		res.sway = zone.groupSway.clone();
		for (int g = 0; g < groups; ++g)
		{
			res.handles[g] = createAccel(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, res.storage.buffer, offset[g], size[g]);
			res.addresses[g] = accelAddress(res.handles[g]);
		}
		return maxScratch;
	}

	private long zoneVertexAddress(ZoneRes res, StaticScene.Zone zone, int group)
	{
		return staticPos.address + ((long) res.faceBase + zone.groupFaceBase[group]) * BYTES_PER_FACE_POS;
	}

	private void buildZoneAccel(VkCommandBuffer upload, ZoneRes res, StaticScene.Zone zone, VkBuf scratch)
	{
		for (int g = 0; g < zone.groupCount(); ++g)
		{
			try (MemoryStack stack = stackPush())
			{
				VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
				fillTriangles(geom.get(0), zoneVertexAddress(res, zone, g), zone.groupFaceCount[g], !zone.groupTranslucent[g]);
				VkAccelerationStructureBuildGeometryInfoKHR info = buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
					VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR, geom);
				recordBuild(upload, info, res.handles[g], scratchAddress(scratch), zone.groupFaceCount[g]);
			}
			// The scratch buffer is reused by the next build.
			memoryBarrier(upload,
				VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR,
				VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
		}
	}

	private void destroyZoneAccel(ZoneRes res)
	{
		for (long handle : res.handles)
		{
			vkDestroyAccelerationStructureKHR(device, handle, null);
		}
		res.handles = new long[0];
		res.addresses = new long[0];
		res.level = new int[0];
		res.roof = new int[0];
		res.faceOffset = new int[0];
		res.translucent = new boolean[0];
		res.water = new boolean[0];
		res.sway = new boolean[0];
		if (res.storage != null)
		{
			ctx.destroyBuffer(res.storage);
			res.storage = null;
		}
	}

	private static void refreshRoofFlags(StaticSet set)
	{
		Arrays.fill(set.levelHasRoofs, false);
		for (ZoneRes res : set.zones)
		{
			if (res == null)
			{
				continue;
			}
			for (int g = 0; g < res.roof.length; ++g)
			{
				if (res.roof[g] > 0)
				{
					set.levelHasRoofs[res.level[g]] = true;
				}
			}
		}
	}

	private void freeAllStatic()
	{
		for (Integer id : new ArrayList<>(staticSets.keySet()))
		{
			removeStaticSet(id);
		}
	}

	// Drains the queue and puts the frame fence back into the unsignalled state the next submit
	// requires; leaving it signalled would let the following frame skip its wait entirely.
	private void idle()
	{
		vkDeviceWaitIdle(device);
		if (fencePending)
		{
			check(vkResetFences(device, fence), "vkResetFences");
			fencePending = false;
		}
	}

	/** Waits for the previous frame's GPU work so host-visible buffers can be rewritten. */
	public void beginFrame()
	{
		if (fencePending)
		{
			check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "vkWaitForFences");
			check(vkResetFences(device, fence), "vkResetFences");
			fencePending = false;
			try (MemoryStack stack = stackPush())
			{
				LongBuffer stamps = stack.mallocLong(STAMPS);
				if (vkGetQueryPoolResults(device, timestampPool, 0, STAMPS, stamps, Long.BYTES, VK_QUERY_RESULT_64_BIT) == VK_SUCCESS)
				{
					lastGpuMillis = (stamps.get(STAMPS - 1) - stamps.get(0)) * ctx.timestampPeriod / 1_000_000.0;
					for (int i = 0; i < PASS_NAMES.length; ++i)
					{
						passMillis[i] = (stamps.get(i + 1) - stamps.get(i)) * ctx.timestampPeriod / 1_000_000.0;
					}
				}
			}
			averageLogLuminance = exposureReadback.mapped.asFloatBuffer().get(0);
		}
	}

	public int outputWidth()
	{
		return outputWidth;
	}

	public int outputHeight()
	{
		return outputHeight;
	}

	/** The hit distance the last finished frame found at a pixel, or a negative value for sky. Waits for the GPU. */
	public float readbackDepth(int x, int y)
	{
		if (output == null || x < 0 || y < 0 || x >= outputWidth || y >= outputHeight)
		{
			return -1f;
		}
		ByteBuffer pixel = readbackImage(historyPos[parity], x, y, 1, 1, 16);
		try
		{
			return pixel.getFloat(12);
		}
		finally
		{
			MemoryUtil.memFree(pixel);
		}
	}

	/** The last finished frame as shown, ARGB row by row. Waits for the GPU. */
	public int[] readbackOutput()
	{
		ByteBuffer rgba = readbackImage(output, 0, 0, outputWidth, outputHeight, 4);
		try
		{
			int[] argb = new int[outputWidth * outputHeight];
			for (int i = 0; i < argb.length; ++i)
			{
				int o = i * 4;
				argb[i] = 0xff000000 | (rgba.get(o) & 0xff) << 16 | (rgba.get(o + 1) & 0xff) << 8 | (rgba.get(o + 2) & 0xff);
			}
			return argb;
		}
		finally
		{
			MemoryUtil.memFree(rgba);
		}
	}

	/** The last finished frame's accumulated colour, linear RGB floats row by row. Waits for the GPU. */
	public float[] readbackColor()
	{
		ByteBuffer halves = readbackImage(historyColor[parity], 0, 0, outputWidth, outputHeight, 8);
		try
		{
			float[] rgb = new float[outputWidth * outputHeight * 3];
			for (int i = 0; i < outputWidth * outputHeight; ++i)
			{
				rgb[i * 3] = halfToFloat(halves.getShort(i * 8));
				rgb[i * 3 + 1] = halfToFloat(halves.getShort(i * 8 + 2));
				rgb[i * 3 + 2] = halfToFloat(halves.getShort(i * 8 + 4));
			}
			return rgb;
		}
		finally
		{
			MemoryUtil.memFree(halves);
		}
	}

	private static float halfToFloat(short h)
	{
		int sign = (h >> 15) & 1;
		int exponent = (h >> 10) & 0x1f;
		int mantissa = h & 0x3ff;
		float value;
		if (exponent == 0)
		{
			value = mantissa * (float) Math.scalb(1.0, -24);
		}
		else if (exponent == 31)
		{
			value = mantissa == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
		}
		else
		{
			value = (1f + mantissa / 1024f) * (float) Math.scalb(1.0, exponent - 15);
		}
		return sign == 0 ? value : -value;
	}

	// Copies a rectangle of a storage image into host memory; the caller frees the buffer.
	private ByteBuffer readbackImage(Img img, int x, int y, int width, int height, int bytesPerPixel)
	{
		idle();
		long bytes = (long) width * height * bytesPerPixel;
		VkBuf staging = ctx.createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		VkCommandBuffer cmd = ctx.beginOneTime();
		try (MemoryStack stack = stackPush())
		{
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
			region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.get(0).imageOffset().x(x).y(y).z(0);
			region.get(0).imageExtent().width(width).height(height).depth(1);
			vkCmdCopyImageToBuffer(cmd, img.image, VK_IMAGE_LAYOUT_GENERAL, staging.buffer, region);
		}
		ctx.endOneTimeAndWait(cmd);
		ByteBuffer out = MemoryUtil.memAlloc((int) bytes);
		MemoryUtil.memCopy(MemoryUtil.memAddress(staging.mapped), MemoryUtil.memAddress(out), bytes);
		ctx.destroyBuffer(staging);
		return out;
	}

	/** Makes the next frame start its history afresh, as when the accumulation changes its units. */
	public void resetHistory()
	{
		hasHistory = false;
	}

	/** Mean log luminance of the last finished frame before exposure, or NaN before any frame. */
	public double averageLogLuminance()
	{
		return averageLogLuminance;
	}

	/** GPU time of the most recently completed frame, in milliseconds. */
	public double lastGpuMillis()
	{
		return lastGpuMillis;
	}

	/** The last frame's GPU time by stage, for the log. */
	public String passReport()
	{
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < PASS_NAMES.length; ++i)
		{
			out.append(i == 0 ? "" : ", ").append(PASS_NAMES[i]).append(' ').append(String.format("%.1f", passMillis[i]));
		}
		return out.toString();
	}

	private void stamp(VkCommandBuffer cmd, int index)
	{
		vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, timestampPool, index);
	}

	/**
	 * Makes sure the exported output image and the history images have the requested size.
	 *
	 * @return true when a new output image was created and must be re-imported by OpenGL
	 */
	public boolean ensureOutput(int width, int height)
	{
		if (output != null && outputWidth == width && outputHeight == height)
		{
			return false;
		}
		idle();
		destroyOutput();

		output = createImage(width, height, OUTPUT_FORMAT, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, true);
		int scratchUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
		sample = createImage(width, height, HISTORY_COLOR_FORMAT, scratchUsage, false);
		albedo = createImage(width, height, OUTPUT_FORMAT, scratchUsage, false);
		normal = createImage(width, height, HISTORY_COLOR_FORMAT, scratchUsage, false);
		shafts = createImage(width, height, HISTORY_COLOR_FORMAT, scratchUsage, false);
		bloomSource = createImage(width, height, HISTORY_COLOR_FORMAT, scratchUsage, false);
		for (int i = 0; i < 2; ++i)
		{
			bloomBlur[i] = createImage((width + 3) / 4, (height + 3) / 4, HISTORY_COLOR_FORMAT, scratchUsage, false);
			diffuseBlur[i] = createImage((width + 3) / 4, (height + 3) / 4, HISTORY_COLOR_FORMAT, scratchUsage, false);
		}
		for (int i = 0; i < 2; ++i)
		{
			historyColor[i] = createImage(width, height, HISTORY_COLOR_FORMAT, scratchUsage, false);
			historyPos[i] = createImage(width, height, HISTORY_POS_FORMAT, scratchUsage, false);
			moments[i] = createImage(width, height, HISTORY_COLOR_FORMAT, scratchUsage, false);
			filter[i] = createImage(width, height, HISTORY_COLOR_FORMAT, scratchUsage, false);
		}
		initializeHistory();

		outputWidth = width;
		outputHeight = height;
		outputUninitialized = true;
		hasHistory = false;
		parity = 0;

		for (int set = 0; set < 2; ++set)
		{
			long handle = descriptorSets[set];
			writeImageDescriptor(handle, BINDING_OUTPUT, output.view);
			writeImageDescriptor(handle, BINDING_SAMPLE, sample.view);
			writeImageDescriptor(handle, BINDING_ALBEDO, albedo.view);
			writeImageDescriptor(handle, BINDING_NORMAL, normal.view);
			writeImageDescriptor(handle, BINDING_SHAFTS, shafts.view);
			writeImageDescriptor(handle, BINDING_BLOOM_SOURCE, bloomSource.view);
			writeImageDescriptor(handle, BINDING_BLOOM_A, bloomBlur[0].view);
			writeImageDescriptor(handle, BINDING_BLOOM_B, bloomBlur[1].view);
			writeImageDescriptor(handle, BINDING_DIFFUSE_A, diffuseBlur[0].view);
			writeImageDescriptor(handle, BINDING_DIFFUSE_B, diffuseBlur[1].view);
			writeImageDescriptor(handle, BINDING_PREV_MOMENTS, moments[set].view);
			writeImageDescriptor(handle, BINDING_CURR_MOMENTS, moments[1 - set].view);
			writeImageDescriptor(handle, BINDING_FILTER_A, filter[0].view);
			writeImageDescriptor(handle, BINDING_FILTER_B, filter[1].view);
			writeImageDescriptor(handle, BINDING_PREV_COLOR, historyColor[set].view);
			writeImageDescriptor(handle, BINDING_CURR_COLOR, historyColor[1 - set].view);
			writeImageDescriptor(handle, BINDING_PREV_POS, historyPos[set].view);
			writeImageDescriptor(handle, BINDING_CURR_POS, historyPos[1 - set].view);
		}
		log.debug("Output image {}x{} ({} bytes)", width, height, output.allocationSize);
		return true;
	}

	private Img createImage(int width, int height, int format, int usage, boolean exported)
	{
		return createImage(width, height, 1, format, usage, exported);
	}

	private Img createImage(int width, int height, int layers, int format, int usage, boolean exported)
	{
		Img img = new Img();
		try (MemoryStack stack = stackPush())
		{
			VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
				.imageType(VK_IMAGE_TYPE_2D)
				.format(format)
				.mipLevels(1)
				.arrayLayers(layers)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(usage)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			imageInfo.extent().width(width).height(height).depth(1);
			if (exported)
			{
				VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
					.handleTypes(ExternalHandles.MEMORY_HANDLE_TYPE);
				imageInfo.pNext(external.address());
			}
			LongBuffer pImage = stack.mallocLong(1);
			check(vkCreateImage(device, imageInfo, null, pImage), "vkCreateImage");
			img.image = pImage.get(0);

			VkMemoryDedicatedRequirements dedicatedReq = VkMemoryDedicatedRequirements.calloc(stack).sType$Default();
			VkMemoryRequirements2 req = VkMemoryRequirements2.calloc(stack).sType$Default().pNext(dedicatedReq.address());
			VkImageMemoryRequirementsInfo2 reqInfo = VkImageMemoryRequirementsInfo2.calloc(stack).sType$Default().image(img.image);
			vkGetImageMemoryRequirements2(device, reqInfo, req);
			img.allocationSize = req.memoryRequirements().size();

			VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default()
				.allocationSize(img.allocationSize)
				.memoryTypeIndex(ctx.findMemoryType(req.memoryRequirements().memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
			if (exported)
			{
				VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(img.image);
				VkExportMemoryAllocateInfo export = VkExportMemoryAllocateInfo.calloc(stack).sType$Default()
					.handleTypes(ExternalHandles.MEMORY_HANDLE_TYPE)
					.pNext(dedicated.address());
				alloc.pNext(export.address());
			}
			LongBuffer pMemory = stack.mallocLong(1);
			check(vkAllocateMemory(device, alloc, null, pMemory), "vkAllocateMemory");
			img.memory = pMemory.get(0);
			check(vkBindImageMemory(device, img.image, img.memory, 0), "vkBindImageMemory");

			if (exported)
			{
				img.handle = ExternalHandles.exportMemory(device, stack, img.memory);
			}

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
				.image(img.image)
				.viewType(layers > 1 ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D)
				.format(format);
			fillColorRange(viewInfo.subresourceRange());
			viewInfo.subresourceRange().layerCount(layers);
			LongBuffer pView = stack.mallocLong(1);
			check(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView");
			img.view = pView.get(0);
		}
		return img;
	}

	private static void fillColorRange(VkImageSubresourceRange range)
	{
		range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
			.baseMipLevel(0)
			.levelCount(1)
			.baseArrayLayer(0)
			.layerCount(1);
	}

	// History images stay in GENERAL layout for their whole life; the first frame after a
	// resize ignores their contents through FLAG_RESET_HISTORY.
	private void initializeHistory()
	{
		VkCommandBuffer init = ctx.beginOneTime();
		try (MemoryStack stack = stackPush())
		{
			VkClearColorValue clear = VkClearColorValue.calloc(stack);
			VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
			fillColorRange(range.get(0));
			for (Img img : new Img[]{sample, albedo, normal, shafts, bloomSource, bloomBlur[0], bloomBlur[1], diffuseBlur[0], diffuseBlur[1], historyColor[0], historyPos[0], moments[0], filter[0],
				historyColor[1], historyPos[1], moments[1], filter[1]})
			{
				imageBarrier(init, img.image, VK_IMAGE_LAYOUT_UNDEFINED,
					VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
					VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
				vkCmdClearColorImage(init, img.image, VK_IMAGE_LAYOUT_GENERAL, clear, range);
			}
		}
		memoryBarrier(init,
			VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
			VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
		ctx.endOneTimeAndWait(init);
	}

	private void destroyOutput()
	{
		if (output == null)
		{
			return;
		}
		destroyImage(output);
		destroyImage(sample);
		destroyImage(albedo);
		destroyImage(normal);
		destroyImage(shafts);
		destroyImage(bloomSource);
		destroyImage(bloomBlur[0]);
		destroyImage(bloomBlur[1]);
		destroyImage(diffuseBlur[0]);
		destroyImage(diffuseBlur[1]);
		output = null;
		sample = null;
		albedo = null;
		normal = null;
		shafts = null;
		bloomSource = null;
		bloomBlur[0] = null;
		bloomBlur[1] = null;
		diffuseBlur[0] = null;
		diffuseBlur[1] = null;
		for (int i = 0; i < 2; ++i)
		{
			destroyImage(historyColor[i]);
			destroyImage(historyPos[i]);
			destroyImage(moments[i]);
			destroyImage(filter[i]);
			historyColor[i] = null;
			historyPos[i] = null;
			moments[i] = null;
			filter[i] = null;
		}
	}

	private void destroyImage(Img img)
	{
		vkDestroyImageView(device, img.view, null);
		vkDestroyImage(device, img.image, null);
		vkFreeMemory(device, img.memory, null);
	}

	/**
	 * Uploads this frame's dynamic geometry, rebuilds the acceleration
	 * structures and dispatches the ray query pass.
	 *
	 * @param waitForGl whether OpenGL signalled the shared semaphore after its last read of the output image
	 */
	/**
	 * Renders a frame. The binary semaphores shared with OpenGL may only be signalled once per
	 * wait, so a frame that OpenGL will never see passes signalGl false.
	 */
	public void submit(FrameParams params, GeometryBuffer dynamic, GeometryBuffer translucent, GeometryBuffer water, boolean waitForGl, boolean signalGl)
	{
		if (output == null)
		{
			throw new IllegalStateException("submit before ensureOutput");
		}
		if (fencePending)
		{
			throw new IllegalStateException("submit without beginFrame");
		}

		int opaqueFaces = Math.min(dynamic.faces(), MAX_DYNAMIC_FACES);
		int translucentFaces = Math.min(translucent.faces(), MAX_DYNAMIC_FACES - opaqueFaces);
		int waterFaces = Math.min(water.faces(), MAX_DYNAMIC_FACES - opaqueFaces - translucentFaces);
		if (dynamic.faces() + translucent.faces() + water.faces() > MAX_DYNAMIC_FACES && !warnedDynamicOverflow)
		{
			warnedDynamicOverflow = true;
			log.warn("Dynamic geometry exceeded {} faces; extra faces are dropped", MAX_DYNAMIC_FACES);
		}
		int dynamicFaces = opaqueFaces + translucentFaces + waterFaces;
		if (dynamicFaces > 0)
		{
			float shutterTime = params.shutter > 0f ? 1f - shutterRandom.nextFloat() * params.shutter : 1f;
			stageDynamic(dynamic, 0, opaqueFaces, shutterTime);
			stageDynamic(translucent, opaqueFaces, translucentFaces, shutterTime);
			stageDynamic(water, opaqueFaces + translucentFaces, waterFaces, shutterTime);
		}

		int instanceCount = writeInstances(params, opaqueFaces, translucentFaces, waterFaces);
		writeFrameUniforms(params);

		try (MemoryStack stack = stackPush())
		{
			check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
				.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer");
			vkCmdResetQueryPool(cmd, timestampPool, 0, STAMPS);
			vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, timestampPool, 0);

			// Static structures and last frame's history were written by earlier submissions.
			memoryBarrier(cmd,
				VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
				VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
				VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_UNIFORM_READ_BIT);

			if (dynamicFaces > 0)
			{
				VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
				copy.get(0).srcOffset(0).dstOffset(0).size((long) dynamicFaces * BYTES_PER_FACE_POS);
				vkCmdCopyBuffer(cmd, dynamicStagingPos.buffer, dynamicPos.buffer, copy);
				copy.get(0).size((long) dynamicFaces * Integer.BYTES);
				vkCmdCopyBuffer(cmd, dynamicStagingCol.buffer, dynamicCol.buffer, copy);
				vkCmdCopyBuffer(cmd, dynamicStagingTex.buffer, dynamicTex.buffer, copy);
				copy.get(0).size((long) dynamicFaces * BYTES_PER_FACE_UV);
				vkCmdCopyBuffer(cmd, dynamicStagingUv.buffer, dynamicUv.buffer, copy);
				memoryBarrier(cmd,
					VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
					VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
					VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_SHADER_READ_BIT);

				if (opaqueFaces > 0)
				{
					VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
					fillTriangles(geom.get(0), dynamicPos.address, opaqueFaces, true);
					VkAccelerationStructureBuildGeometryInfoKHR info = buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
						VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR, geom);
					recordBuild(cmd, info, dynamicBlas.handle, scratchAddress(dynamicBlas.scratch), opaqueFaces);
				}
				if (translucentFaces > 0)
				{
					VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
					fillTriangles(geom.get(0), dynamicPos.address + (long) opaqueFaces * BYTES_PER_FACE_POS, translucentFaces, false);
					VkAccelerationStructureBuildGeometryInfoKHR info = buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
						VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR, geom);
					recordBuild(cmd, info, dynamicTranslucentBlas.handle, scratchAddress(dynamicTranslucentBlas.scratch), translucentFaces);
				}
				if (waterFaces > 0)
				{
					VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
					fillTriangles(geom.get(0), dynamicPos.address + (long) (opaqueFaces + translucentFaces) * BYTES_PER_FACE_POS, waterFaces, true);
					VkAccelerationStructureBuildGeometryInfoKHR info = buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
						VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR, geom);
					recordBuild(cmd, info, dynamicWaterBlas.handle, scratchAddress(dynamicWaterBlas.scratch), waterFaces);
				}
				memoryBarrier(cmd,
					VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
					VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
			}

			VkAccelerationStructureGeometryKHR.Buffer topGeom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			fillInstances(topGeom.get(0), instances.address);
			VkAccelerationStructureBuildGeometryInfoKHR topInfo = buildInfo(stack, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
				VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR, topGeom);
			recordBuild(cmd, topInfo, tlas.handle, scratchAddress(tlas.scratch), instanceCount);
			memoryBarrier(cmd,
				VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
				VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);

			imageBarrier(cmd, output.image,
				outputUninitialized ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL,
				VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
				VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
			outputUninitialized = false;

			int groupsX = (outputWidth + 7) / 8;
			int groupsY = (outputHeight + 7) / 8;
			vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSets[parity]), null);
			// Every stamp is written every frame, run or not, so the readback never waits on one.
			stamp(cmd, 1);
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, tracePipeline);
			vkCmdDispatch(cmd, groupsX, groupsY, 1);
			stamp(cmd, 2);
			if (!params.pattern)
			{
				if (params.lightShafts > 0f)
				{
					computeBarrier(cmd);
					vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, shaftsPipeline);
					vkCmdDispatch(cmd, groupsX, groupsY, 1);
				}
				stamp(cmd, 3);
				int passes = Math.min(Math.max(params.denoisePasses, 0), MAX_DENOISE_PASSES);
				// The post pass gathers over the finished image, so the last denoiser pass leaves
				// its result in the filter image for it instead of writing the output.
				ByteBuffer push = stack.malloc(PUSH_CONSTANT_SIZE);
				computeBarrier(cmd);
				pushPass(cmd, push, 0, 1, 0, passes);
				vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, resolvePipeline);
				vkCmdDispatch(cmd, groupsX, groupsY, 1);
				vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, atrousPipeline);
				for (int pass = 0; pass < passes; ++pass)
				{
					computeBarrier(cmd);
					pushPass(cmd, push, pass, 1 << pass, pass == passes - 1 ? 2 : 0, passes);
					vkCmdDispatch(cmd, groupsX, groupsY, 1);
				}
				stamp(cmd, 4);
				if (passes > 0)
				{
					if (params.bloom > 0f || params.diffusion > 0f)
					{
						// The blur pass reads the finished frame from the filter image the last denoiser
						// pass wrote, so it is told that pass's parity through the step field.
						int quarterX = ((outputWidth + 3) / 4 + 7) / 8;
						int quarterY = ((outputHeight + 3) / 4 + 7) / 8;
						vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, bloomPipeline);
						computeBarrier(cmd);
						pushPass(cmd, push, 0, passes, 0, passes);
						vkCmdDispatch(cmd, quarterX, quarterY, 1);
						computeBarrier(cmd);
						pushPass(cmd, push, 1, passes, 0, passes);
						vkCmdDispatch(cmd, quarterX, quarterY, 1);
					}
					stamp(cmd, 5);
					computeBarrier(cmd);
					pushPass(cmd, push, passes, 1, 0, passes);
					vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, postPipeline);
					vkCmdDispatch(cmd, groupsX, groupsY, 1);
					stamp(cmd, 6);
					if (params.autoExposure)
					{
						// Meters the luminance the final denoiser pass recorded; one workgroup suffices.
						vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, exposurePipeline);
						vkCmdDispatch(cmd, 1, 1, 1);
					}
				}
				else
				{
					stamp(cmd, 5);
					stamp(cmd, 6);
				}
			}
			else
			{
				for (int i = 3; i <= 6; ++i)
				{
					stamp(cmd, i);
				}
			}

			imageBarrier(cmd, output.image, VK_IMAGE_LAYOUT_GENERAL,
				VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
				VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
			stamp(cmd, STAMPS - 1);

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
					.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT));
			}
			check(vkQueueSubmit(ctx.queue, submit, fence), "vkQueueSubmit");
			fencePending = true;
			++frameIndex;
		}

		if (!params.pattern)
		{
			parity ^= 1;
			prevCamera[0] = params.cameraX;
			prevCamera[1] = params.cameraY;
			prevCamera[2] = params.cameraZ;
			prevCamera[3] = params.zoom;
			System.arraycopy(params.forwardRotation, 0, prevForward, 0, 9);
			hasHistory = true;
		}
	}

	private void computeBarrier(VkCommandBuffer commandBuffer)
	{
		memoryBarrier(commandBuffer,
			VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
			VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
	}

	// Layout must match the Push block in resolve.comp, atrous.comp, bloom.comp and post.comp; last
	// is 0, 1 for a final pass writing the output, or 2 for a final pass handing over to post.
	private void pushPass(VkCommandBuffer commandBuffer, ByteBuffer push, int pass, int step, int last, int passes)
	{
		push.clear();
		push.putInt(pass).putInt(step).putInt(last).putInt(passes).flip();
		vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
	}

	private void imageBarrier(VkCommandBuffer commandBuffer, long image, int oldLayout, int srcStage, int srcAccess, int dstStage, int dstAccess)
	{
		try (MemoryStack stack = stackPush())
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
			fillColorRange(barrier.get(0).subresourceRange());
			vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, null, null, barrier);
		}
	}

	// Copies a face range of a geometry buffer into the dynamic staging buffers at a face offset.
	// With motion blur the positions are taken at this frame's shutter time, between where the
	// faces were last frame and where they are now; the accumulation integrates over frames.
	private void stageDynamic(GeometryBuffer source, int faceOffset, int faces, float shutterTime)
	{
		if (faces == 0)
		{
			return;
		}
		FloatBuffer pos = dynamicStagingPos.mapped.asFloatBuffer();
		pos.position(faceOffset * GeometryBuffer.FLOATS_PER_FACE);
		int floats = faces * GeometryBuffer.FLOATS_PER_FACE;
		if (shutterTime >= 1f)
		{
			pos.put(source.positions(), 0, floats);
		}
		else
		{
			float[] now = source.positions();
			float[] before = source.previousPositions();
			if (motionScratch.length < floats)
			{
				motionScratch = new float[Math.max(floats, motionScratch.length * 2)];
			}
			for (int i = 0; i < floats; ++i)
			{
				motionScratch[i] = before[i] + (now[i] - before[i]) * shutterTime;
			}
			pos.put(motionScratch, 0, floats);
		}
		IntBuffer col = dynamicStagingCol.mapped.asIntBuffer();
		col.position(faceOffset);
		col.put(source.colors(), 0, faces);
		IntBuffer tex = dynamicStagingTex.mapped.asIntBuffer();
		tex.position(faceOffset);
		tex.put(source.textures(), 0, faces);
		FloatBuffer uv = dynamicStagingUv.mapped.asFloatBuffer();
		uv.position(faceOffset * GeometryBuffer.UV_FLOATS_PER_FACE);
		uv.put(source.uvs(), 0, faces * GeometryBuffer.UV_FLOATS_PER_FACE);
	}

	// Whether an untransformed scene's zone lies within the render distance of the camera.
	private static boolean zoneInRange(StaticSet set, int index, FrameParams params)
	{
		if (set.transform != null || params.renderDistance <= 0f)
		{
			return true;
		}
		int zx = index / set.zonesZ;
		int zz = index % set.zonesZ;
		int offsetTiles = (set.zonesX * 8 - 104) / 2;
		float minX = (zx * 8 - offsetTiles) * 128f;
		float minZ = (zz * 8 - offsetTiles) * 128f;
		float dx = Math.max(0f, Math.max(minX - params.cameraX, params.cameraX - (minX + 1024f)));
		float dz = Math.max(0f, Math.max(minZ - params.cameraZ, params.cameraZ - (minZ + 1024f)));
		return dx * dx + dz * dz <= params.renderDistance * params.renderDistance;
	}

	private int writeInstances(FrameParams params, int opaqueFaces, int translucentFaces, int waterFaces)
	{
		VkAccelerationStructureInstanceKHR.Buffer buffer = VkAccelerationStructureInstanceKHR.create(
			MemoryUtil.memAddress(instances.mapped), MAX_INSTANCES);
		int count = 0;
		if (opaqueFaces > 0)
		{
			writeInstance(buffer.get(count++), DYNAMIC_INSTANCE_BIT, dynamicBlas.address, MASK_OPAQUE, null);
		}
		if (waterFaces > 0)
		{
			writeInstance(buffer.get(count++), DYNAMIC_INSTANCE_BIT | (opaqueFaces + translucentFaces), dynamicWaterBlas.address, MASK_WATER, null);
		}
		if (translucentFaces > 0)
		{
			writeInstance(buffer.get(count++), DYNAMIC_INSTANCE_BIT | opaqueFaces, dynamicTranslucentBlas.address, MASK_TRANSLUCENT, null);
		}
		for (StaticSet set : staticSets.values())
		{
			for (int z = 0; z < set.zones.length; ++z)
			{
				ZoneRes res = set.zones[z];
				if (res == null || !zoneInRange(set, z, params))
				{
					continue;
				}
				boolean swayedZone = set.swayed != null && z < set.swayed.length && set.swayed[z];
				boolean displacedZone = set.displaced != null && z < set.displaced.length && set.displaced[z];
				for (int g = 0; g < res.handles.length && count < MAX_INSTANCES; ++g)
				{
					if ((swayedZone && res.sway[g]) || (displacedZone && res.water[g]))
					{
						continue;
					}
					if (groupVisible(set, res.level[g], res.roof[g]))
					{
						writeInstance(buffer.get(count++), res.faceBase + res.faceOffset[g], res.addresses[g],
							res.translucent[g] ? MASK_TRANSLUCENT : res.water[g] ? MASK_WATER : MASK_OPAQUE, set.transform);
					}
					else if (params.roofOcclusion && hiddenRoof(set, res.level[g], res.roof[g]))
					{
						// The room below a hidden roof is still roofed as far as the sun and sky know.
						writeInstance(buffer.get(count++), res.faceBase + res.faceOffset[g], res.addresses[g], MASK_HIDDEN_ROOF, set.transform);
					}
				}
			}
		}
		return count;
	}

	// Same rule as the GPU plugin's Zone.renderOpaque: whole levels within range are drawn,
	// except roofs above the current level that the client asked to hide.
	private static boolean hiddenRoof(StaticSet set, int level, int roof)
	{
		return level >= set.minLevel && level <= set.maxLevel && roof != 0 && level > set.level && set.hiddenRoofIds.contains(roof);
	}

	private static boolean groupVisible(StaticSet set, int level, int roof)
	{
		if (level < set.minLevel || level > set.maxLevel)
		{
			return false;
		}
		if (roof == 0 || !set.levelHasRoofs[level] || set.hiddenRoofIds.isEmpty() || level <= set.level)
		{
			return true;
		}
		return !set.hiddenRoofIds.contains(roof);
	}

	private static void writeInstance(VkAccelerationStructureInstanceKHR instance, int customIndex, long accelAddress, int mask, float[] transform)
	{
		FloatBuffer m = instance.transform().matrix();
		for (int i = 0; i < 12; ++i)
		{
			m.put(i, transform == null ? (i == 0 || i == 5 || i == 10 ? 1f : 0f) : transform[i]);
		}
		instance.instanceCustomIndex(customIndex)
			.mask(mask)
			.instanceShaderBindingTableRecordOffset(0)
			.flags(0)
			.accelerationStructureReference(accelAddress);
	}

	// Layout must match the std140 Frame block in rt.comp.
	private void writeFrameUniforms(FrameParams p)
	{
		ByteBuffer b = frameUbo.mapped;
		b.clear();
		b.putFloat(p.cameraX).putFloat(p.cameraY).putFloat(p.cameraZ).putFloat(p.zoom);
		putRows(b, p.inverseRotation);
		b.putFloat(prevCamera[0]).putFloat(prevCamera[1]).putFloat(prevCamera[2]).putFloat(prevCamera[3]);
		putRows(b, prevForward);
		b.putFloat(p.sunX).putFloat(p.sunY).putFloat(p.sunZ).putFloat(p.sunIntensity);
		b.putFloat(p.skyR).putFloat(p.skyG).putFloat(p.skyB).putFloat(p.ambient);
		b.putFloat(p.sunAngularRadius).putFloat(p.exposure).putFloat(p.historyFrames).putFloat(p.dynamicHistoryFrames);
		int flags = (p.cullBackfaces ? FLAG_CULL : 0)
			| (p.shadows ? FLAG_SHADOWS : 0)
			| (p.pattern ? FLAG_PATTERN : 0)
			| (hasHistory ? 0 : FLAG_RESET_HISTORY)
			| (p.skybox ? FLAG_SKYBOX : 0)
			| (p.textures ? FLAG_TEXTURES : 0)
			| (p.antialias ? FLAG_ANTIALIAS : 0)
			| (p.water ? FLAG_WATER : 0)
			| (p.proceduralSky ? FLAG_PROCEDURAL_SKY : 0)
			| (p.clouds ? FLAG_CLOUDS : 0)
			| (p.cloudShadows ? FLAG_CLOUD_SHADOWS : 0)
			| (p.caustics ? FLAG_CAUSTICS : 0)
			| (p.rainRipples ? FLAG_RAIN_RIPPLES : 0)
			| (p.puddles ? FLAG_PUDDLES : 0)
			| (p.terrainTextures ? FLAG_TERRAIN_TEXTURES : 0)
			| (p.terrainSmoothing ? FLAG_SMOOTH_TERRAIN : 0)
			| (p.runoff ? FLAG_RUNOFF : 0)
			| (p.mistEverywhere ? FLAG_MIST_EVERYWHERE : 0)
			| (p.fireflies ? FLAG_FIREFLIES : 0)
			| (p.dustMotes ? FLAG_DUST : 0)
			| (p.still ? FLAG_STILL : 0)
			| (p.thinLens ? FLAG_THIN_LENS : 0)
			| (p.physicalSky ? FLAG_PHYSICAL_SKY : 0)
			| (p.wildlife ? FLAG_WILDLIFE : 0)
			| (p.underwater ? FLAG_UNDERWATER : 0)
			| (p.heatShimmer ? FLAG_SHIMMER : 0)
			| (p.rainbows ? FLAG_RAINBOWS : 0)
			| (p.focusPeaking ? FLAG_PEAKING : 0)
			| (p.textureDisplacement ? FLAG_DISPLACEMENT : 0);
		b.putInt(frameIndex).putInt(flags).putInt(outputWidth).putInt(outputHeight);
		b.putFloat(p.skyboxRotation).putFloat(p.backgroundR).putFloat(p.backgroundG).putFloat(p.backgroundB);
		b.putFloat(p.denoiseLuminance).putFloat(DENOISE_NORMAL_POWER).putFloat(DENOISE_POSITION_SIGMA).putFloat(p.plumeCount);
		b.putFloat(p.sunR).putFloat(p.sunG).putFloat(p.sunB).putFloat(0f);
		b.putFloat(p.bounces).putFloat(p.aperture).putFloat(p.focusDistance).putFloat(p.waveStrength);
		b.putFloat(p.shutter).putFloat(p.gameCycle).putFloat(p.bumpStrength).putFloat(0f);
		b.putFloat(p.cloud).putFloat(p.fogAmount).putFloat(p.rain).putFloat(p.snow);
		b.putFloat(p.wetness).putFloat(p.snowCover).putFloat(p.windOffsetX).putFloat(p.windOffsetZ);
		b.putFloat(p.fogR).putFloat(p.fogG).putFloat(p.fogB).putFloat(p.flash);
		b.putFloat(p.timeSeconds).putFloat(p.mist).putFloat(p.mistGridSize).putFloat(p.mistGridOffset);
		b.putFloat(p.mistR).putFloat(p.mistG).putFloat(p.mistB).putFloat(p.lightShafts);
		b.putFloat(p.vignette).putFloat(p.bloom).putFloat(p.renderDistance).putFloat(p.distanceFade);
		b.putFloat(p.filmGrain).putFloat(p.chromaticAberration).putFloat(p.aerialPerspective).putFloat(p.sunUp);
		b.putFloat(p.lightCount).putFloat(p.lightStrength).putFloat(p.surfaceGloss).putFloat(p.surfaceGlossExponent);
		// The blur runs at quarter resolution and the visible radius spans about two and a half sigmas.
		b.putFloat(p.emissiveStrength).putFloat(p.glossyReflections ? 1f : 0f).putFloat(p.terrainBump).putFloat(p.diffusionRadius / 2.5f / 4f);
		b.putFloat(p.contrast).putFloat(p.saturation).putFloat(p.temperature).putFloat(p.diffusion);
		b.putFloat(p.windVelocityX).putFloat(p.windVelocityZ).putFloat(p.sunDiscRadius).putFloat(p.rainLength);
		b.putFloat(p.skyAmbientR).putFloat(p.skyAmbientG).putFloat(p.skyAmbientB).putFloat(p.rainSpeed);
		b.putFloat(p.eyeX).putFloat(p.eyeY).putFloat(p.eyeZ).putFloat(p.unseenDarkness);
		// Count, wisp count and style share one float: 10, 6 and 2 bits, exact in a float.
		b.putFloat(p.guideR).putFloat(p.guideG).putFloat(p.guideB).putFloat(p.guideCount | p.guideWisps << 10 | p.guideStyle << 16);
		b.putFloat(p.markerCount).putFloat(p.markerStrength).putFloat(p.rimStrength).putFloat(p.lensFlare);
		for (float c : p.highlightColours)
		{
			b.putFloat(c);
		}
		b.putFloat(p.season).putFloat(p.seasonProgress).putFloat(p.leafFall).putFloat(p.petals);
		float[] s = p.starRotation;
		b.putFloat(s[0]).putFloat(s[1]).putFloat(s[2]).putFloat(p.starBrightness);
		b.putFloat(s[3]).putFloat(s[4]).putFloat(s[5]).putFloat(p.auroraWeight);
		b.putFloat(s[6]).putFloat(s[7]).putFloat(s[8]).putFloat(p.treeCount);
		b.putFloat(p.moonSunX).putFloat(p.moonSunY).putFloat(p.moonSunZ).putFloat(p.moonFraction);
		b.putFloat(p.printCount).putFloat(p.footprintStrength).putFloat(p.waterSurfaceY).putFloat(p.dirtLayer);
		for (float c : p.fogRing)
		{
			b.putFloat(c);
		}
		b.putInt(p.flags2).putInt(0).putInt(0).putInt(0);
		if (b.position() > FRAME_UBO_SIZE)
		{
			throw new IllegalStateException("Frame uniforms exceed the buffer: " + b.position() + " of " + FRAME_UBO_SIZE + " bytes");
		}
	}

	private static void putRows(ByteBuffer b, float[] rows)
	{
		for (int r = 0; r < 3; ++r)
		{
			b.putFloat(rows[r * 3]).putFloat(rows[r * 3 + 1]).putFloat(rows[r * 3 + 2]).putFloat(0f);
		}
	}

	public void destroy()
	{
		vkDeviceWaitIdle(device);
		destroyOutput();
		freeAllStatic();
		ctx.destroyBuffer(staticPos);
		ctx.destroyBuffer(staticCol);
		ctx.destroyBuffer(staticTex);
		ctx.destroyBuffer(staticUv);
		destroyAccel(tlas);
		destroyAccel(dynamicBlas);
		destroyAccel(dynamicTranslucentBlas);
		destroyAccel(dynamicWaterBlas);
		ctx.destroyBuffer(instances);
		ctx.destroyBuffer(dynamicStagingPos);
		ctx.destroyBuffer(dynamicStagingCol);
		ctx.destroyBuffer(dynamicStagingTex);
		ctx.destroyBuffer(dynamicStagingUv);
		ctx.destroyBuffer(dynamicPos);
		ctx.destroyBuffer(dynamicCol);
		ctx.destroyBuffer(dynamicTex);
		ctx.destroyBuffer(dynamicUv);
		if (gameTextures != null)
		{
			destroyImage(gameTextures);
		}
		vkDestroySampler(device, textureSampler, null);
		ctx.destroyBuffer(frameUbo);
		ctx.destroyBuffer(textureAnimation);
		ctx.destroyBuffer(waterTypes);
		ctx.destroyBuffer(mistGrid);
		ctx.destroyBuffer(exposureReadback);
		ctx.destroyBuffer(lights);
		ctx.destroyBuffer(guide);
		ctx.destroyBuffer(plumes);
		ctx.destroyBuffer(markers);
		ctx.destroyBuffer(prints);
		ctx.destroyBuffer(trees);
		ctx.destroyBuffer(cells);
		ctx.destroyBuffer(materials);
		ctx.destroyBuffer(terrainHeights);
		ctx.destroyBuffer(runoff);
		if (skybox != null)
		{
			destroyImage(skybox);
		}
		if (starMap != null)
		{
			destroyImage(starMap);
		}
		if (skyLut != null)
		{
			destroyImage(skyLut);
		}
		vkDestroySampler(device, skyboxSampler, null);
		vkDestroyPipeline(device, tracePipeline, null);
		vkDestroyPipeline(device, resolvePipeline, null);
		vkDestroyPipeline(device, atrousPipeline, null);
		vkDestroyPipeline(device, postPipeline, null);
		vkDestroyPipeline(device, bloomPipeline, null);
		vkDestroyPipeline(device, exposurePipeline, null);
		vkDestroyPipeline(device, shaftsPipeline, null);
		vkDestroyPipelineLayout(device, pipelineLayout, null);
		vkDestroyDescriptorPool(device, descriptorPool, null);
		vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
		vkDestroySemaphore(device, semaphoreVkDone, null);
		vkDestroySemaphore(device, semaphoreGlDone, null);
		vkDestroyFence(device, fence, null);
		vkDestroyQueryPool(device, timestampPool, null);
		vkFreeCommandBuffers(device, ctx.commandPool, cmd);
	}

	private void destroyAccel(Accel accel)
	{
		vkDestroyAccelerationStructureKHR(device, accel.handle, null);
		ctx.destroyBuffer(accel.storage);
		ctx.destroyBuffer(accel.scratch);
	}
}
