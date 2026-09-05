package rltx.vk;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK13.*;
import static rltx.vk.VkUtil.check;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSubmitInfo;

/**
 * Vulkan instance, device and queue on the same physical GPU as the OpenGL
 * context, plus the small allocation helpers the renderer needs.
 */
@Slf4j
public final class VkContext
{
	private static final String[] REQUIRED_EXTENSIONS = requiredExtensions();

	private static String[] requiredExtensions()
	{
		String[] common = {
			VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
			VK_KHR_RAY_QUERY_EXTENSION_NAME,
			VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
		};
		String[] all = Arrays.copyOf(common, common.length + ExternalHandles.DEVICE_EXTENSIONS.length);
		System.arraycopy(ExternalHandles.DEVICE_EXTENSIONS, 0, all, common.length, ExternalHandles.DEVICE_EXTENSIONS.length);
		return all;
	}

	private static final int INIT_THREAD_STACK_BYTES = 4 << 20;

	public final VkInstance instance;
	public final VkPhysicalDevice physicalDevice;
	public final VkDevice device;
	public final VkQueue queue;
	public final int queueFamily;
	public final long commandPool;
	public final int scratchAlignment;
	/** Nanoseconds per timestamp tick. */
	public final float timestampPeriod;
	public final String deviceName;
	private final VkPhysicalDeviceMemoryProperties memoryProperties;

	private VkContext(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device, VkQueue queue,
		int queueFamily, long commandPool, int scratchAlignment, float timestampPeriod, String deviceName,
		VkPhysicalDeviceMemoryProperties memoryProperties)
	{
		this.timestampPeriod = timestampPeriod;
		this.instance = instance;
		this.physicalDevice = physicalDevice;
		this.device = device;
		this.queue = queue;
		this.queueFamily = queueFamily;
		this.commandPool = commandPool;
		this.scratchAlignment = scratchAlignment;
		this.deviceName = deviceName;
		this.memoryProperties = memoryProperties;
	}

	public static VkContext create(byte[] glDeviceUuid)
	{
		// LWJGL's VkInstance constructor enumerates every device extension on the thread-local
		// MemoryStack, which overflows the default 64 KiB with current NVIDIA drivers. The size is
		// fixed when LWJGL loads, so use a dedicated thread and give it a larger stack.
		VkContext[] result = new VkContext[1];
		Throwable[] failure = new Throwable[1];
		Thread init = new Thread(() ->
		{
			try
			{
				installLargeMemoryStack();
				result[0] = createOnCurrentThread(glDeviceUuid);
			}
			catch (Throwable t)
			{
				failure[0] = t;
			}
		}, "rltx-vulkan-init");
		init.start();
		try
		{
			init.join();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while creating the Vulkan context", e);
		}
		if (failure[0] != null)
		{
			throw new IllegalStateException("Vulkan initialisation failed", failure[0]);
		}
		return result[0];
	}

	@SuppressWarnings("unchecked")
	private static void installLargeMemoryStack()
	{
		try
		{
			Field tls = MemoryStack.class.getDeclaredField("TLS");
			tls.setAccessible(true);
			((ThreadLocal<MemoryStack>) tls.get(null)).set(MemoryStack.create(INIT_THREAD_STACK_BYTES));
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("Cannot enlarge LWJGL's MemoryStack for Vulkan initialisation", e);
		}
	}

	private static VkContext createOnCurrentThread(byte[] glDeviceUuid)
	{
		VkInstance instance = createInstance();
		VkPhysicalDevice physicalDevice = pickPhysicalDevice(instance, glDeviceUuid);

		String name;
		int scratchAlignment;
		float timestampPeriod;
		int queueFamily;
		try (MemoryStack stack = stackPush())
		{
			VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
			VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(asProps.address());
			vkGetPhysicalDeviceProperties2(physicalDevice, props);
			name = props.properties().deviceNameString();
			scratchAlignment = asProps.minAccelerationStructureScratchOffsetAlignment();
			timestampPeriod = props.properties().limits().timestampPeriod();

			IntBuffer count = stack.mallocInt(1);
			vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
			VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
			vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);
			queueFamily = -1;
			for (int i = 0; i < families.capacity(); ++i)
			{
				int flags = families.get(i).queueFlags();
				if ((flags & VK_QUEUE_COMPUTE_BIT) != 0 && (flags & VK_QUEUE_GRAPHICS_BIT) != 0)
				{
					queueFamily = i;
					break;
				}
			}
			if (queueFamily < 0)
			{
				throw new IllegalStateException("No graphics+compute queue family on " + name);
			}
		}

		VkDevice device;
		VkQueue queue;
		long commandPool;
		try (MemoryStack stack = stackPush())
		{
			VkPhysicalDeviceRayQueryFeaturesKHR rayQuery = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
			VkPhysicalDeviceAccelerationStructureFeaturesKHR accel = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default().pNext(rayQuery.address());
			VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().pNext(accel.address());
			VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(f12.address());
			vkGetPhysicalDeviceFeatures2(physicalDevice, features);
			if (!rayQuery.rayQuery() || !accel.accelerationStructure() || !f12.bufferDeviceAddress())
			{
				throw new IllegalStateException(name + " lacks ray query support (rayQuery=" + rayQuery.rayQuery()
					+ ", accelerationStructure=" + accel.accelerationStructure()
					+ ", bufferDeviceAddress=" + f12.bufferDeviceAddress() + ")");
			}

			VkPhysicalDeviceRayQueryFeaturesKHR enableRq = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default().rayQuery(true);
			VkPhysicalDeviceAccelerationStructureFeaturesKHR enableAs = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default()
				.accelerationStructure(true).pNext(enableRq.address());
			VkPhysicalDeviceVulkan12Features enable12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default()
				.bufferDeviceAddress(true).pNext(enableAs.address());
			VkPhysicalDeviceFeatures2 enable = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(enable12.address());
			// Out-of-range buffer reads return zero instead of faulting the device.
			enable.features().robustBufferAccess(features.features().robustBufferAccess());

			VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
			queueInfo.get(0).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1f));

			PointerBuffer extensions = stack.mallocPointer(REQUIRED_EXTENSIONS.length);
			for (String ext : REQUIRED_EXTENSIONS)
			{
				extensions.put(stack.UTF8(ext));
			}
			extensions.flip();

			VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default()
				.pNext(enable.address())
				.pQueueCreateInfos(queueInfo)
				.ppEnabledExtensionNames(extensions);
			PointerBuffer pDevice = stack.mallocPointer(1);
			check(vkCreateDevice(physicalDevice, deviceInfo, null, pDevice), "vkCreateDevice");
			device = new VkDevice(pDevice.get(0), physicalDevice, deviceInfo);

			PointerBuffer pQueue = stack.mallocPointer(1);
			vkGetDeviceQueue(device, queueFamily, 0, pQueue);
			queue = new VkQueue(pQueue.get(0), device);

			VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
				.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
				.queueFamilyIndex(queueFamily);
			LongBuffer pPool = stack.mallocLong(1);
			check(vkCreateCommandPool(device, poolInfo, null, pPool), "vkCreateCommandPool");
			commandPool = pPool.get(0);
		}

		VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.calloc();
		vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);

		log.info("Vulkan device: {} (scratch alignment {})", name, scratchAlignment);
		return new VkContext(instance, physicalDevice, device, queue, queueFamily, commandPool, scratchAlignment, timestampPeriod, name, memoryProperties);
	}

	private static VkInstance createInstance()
	{
		try (MemoryStack stack = stackPush())
		{
			VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
				.pApplicationName(stack.UTF8("RLTX"))
				.applicationVersion(VK_MAKE_VERSION(0, 1, 0))
				.pEngineName(stack.UTF8("RLTX"))
				.engineVersion(VK_MAKE_VERSION(0, 1, 0))
				.apiVersion(VK_API_VERSION_1_3);
			VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
			PointerBuffer pInstance = stack.mallocPointer(1);
			check(vkCreateInstance(info, null, pInstance), "vkCreateInstance");
			return new VkInstance(pInstance.get(0), info);
		}
	}

	private static VkPhysicalDevice pickPhysicalDevice(VkInstance instance, byte[] glDeviceUuid)
	{
		try (MemoryStack stack = stackPush())
		{
			IntBuffer count = stack.mallocInt(1);
			check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
			if (count.get(0) == 0)
			{
				throw new IllegalStateException("No Vulkan devices");
			}
			PointerBuffer devices = stack.mallocPointer(count.get(0));
			check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");

			VkPhysicalDevice fallback = null;
			String fallbackName = null;
			for (int i = 0; i < devices.capacity(); ++i)
			{
				VkPhysicalDevice pd = new VkPhysicalDevice(devices.get(i), instance);
				VkPhysicalDeviceIDProperties ids = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
				VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(ids.address());
				vkGetPhysicalDeviceProperties2(pd, props);
				String name = props.properties().deviceNameString();

				byte[] uuid = new byte[VK_UUID_SIZE];
				ids.deviceUUID().get(uuid);
				boolean sameAsGl = Arrays.equals(uuid, glDeviceUuid);
				boolean capable = hasRequiredExtensions(pd);
				log.debug("Vulkan candidate {}: matchesGL={} capable={}", name, sameAsGl, capable);

				if (sameAsGl && capable)
				{
					return pd;
				}
				if (capable && fallback == null)
				{
					fallback = pd;
					fallbackName = name;
				}
			}
			if (fallback == null)
			{
				throw new IllegalStateException("No Vulkan device supports " + Arrays.toString(REQUIRED_EXTENSIONS));
			}
			log.warn("No Vulkan device matches the OpenGL device UUID; using {}. Image sharing may fail.", fallbackName);
			return fallback;
		}
	}

	private static boolean hasRequiredExtensions(VkPhysicalDevice pd)
	{
		try (MemoryStack stack = stackPush())
		{
			IntBuffer count = stack.mallocInt(1);
			check(vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, count, null), "vkEnumerateDeviceExtensionProperties");
			VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0));
			try
			{
				check(vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, count, props), "vkEnumerateDeviceExtensionProperties");
				Set<String> names = new HashSet<>();
				for (int i = 0; i < props.capacity(); ++i)
				{
					names.add(props.get(i).extensionNameString());
				}
				for (String required : REQUIRED_EXTENSIONS)
				{
					if (!names.contains(required))
					{
						return false;
					}
				}
				return true;
			}
			finally
			{
				props.free();
			}
		}
	}

	public int findMemoryType(int typeBits, int required)
	{
		for (int i = 0; i < memoryProperties.memoryTypeCount(); ++i)
		{
			if ((typeBits & (1 << i)) != 0 && (memoryProperties.memoryTypes(i).propertyFlags() & required) == required)
			{
				return i;
			}
		}
		throw new IllegalStateException("No memory type with flags " + required + " in mask " + Integer.toBinaryString(typeBits));
	}

	public VkBuf createBuffer(long size, int usage, int memoryFlags)
	{
		try (MemoryStack stack = stackPush())
		{
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
				.size(size)
				.usage(usage)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer pBuffer = stack.mallocLong(1);
			check(vkCreateBuffer(device, info, null, pBuffer), "vkCreateBuffer");
			long buffer = pBuffer.get(0);

			VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
			vkGetBufferMemoryRequirements(device, buffer, req);

			VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default()
				.allocationSize(req.size())
				.memoryTypeIndex(findMemoryType(req.memoryTypeBits(), memoryFlags));
			boolean wantsAddress = (usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0;
			if (wantsAddress)
			{
				VkMemoryAllocateFlagsInfo flags = VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default()
					.flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
				alloc.pNext(flags.address());
			}
			LongBuffer pMemory = stack.mallocLong(1);
			check(vkAllocateMemory(device, alloc, null, pMemory), "vkAllocateMemory");
			long memory = pMemory.get(0);
			check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");

			long address = 0;
			if (wantsAddress)
			{
				VkBufferDeviceAddressInfo addressInfo = VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer);
				address = vkGetBufferDeviceAddress(device, addressInfo);
			}

			ByteBuffer mapped = null;
			if ((memoryFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0)
			{
				PointerBuffer pData = stack.mallocPointer(1);
				check(vkMapMemory(device, memory, 0, size, 0, pData), "vkMapMemory");
				mapped = MemoryUtil.memByteBuffer(pData.get(0), (int) size);
			}
			return new VkBuf(buffer, memory, size, address, mapped);
		}
	}

	public void destroyBuffer(VkBuf buf)
	{
		if (buf == null)
		{
			return;
		}
		if (buf.mapped != null)
		{
			vkUnmapMemory(device, buf.memory);
		}
		vkDestroyBuffer(device, buf.buffer, null);
		vkFreeMemory(device, buf.memory, null);
	}

	public VkCommandBuffer allocateCommandBuffer()
	{
		try (MemoryStack stack = stackPush())
		{
			VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
				.commandPool(commandPool)
				.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
				.commandBufferCount(1);
			PointerBuffer pCmd = stack.mallocPointer(1);
			check(vkAllocateCommandBuffers(device, info, pCmd), "vkAllocateCommandBuffers");
			return new VkCommandBuffer(pCmd.get(0), device);
		}
	}

	public VkCommandBuffer beginOneTime()
	{
		VkCommandBuffer cmd = allocateCommandBuffer();
		try (MemoryStack stack = stackPush())
		{
			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
				.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer");
		}
		return cmd;
	}

	public void endOneTimeAndWait(VkCommandBuffer cmd)
	{
		check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
		try (MemoryStack stack = stackPush())
		{
			VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd));
			check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "vkQueueSubmit");
			check(vkQueueWaitIdle(queue), "vkQueueWaitIdle");
		}
		vkFreeCommandBuffers(device, commandPool, cmd);
	}

	public void destroy()
	{
		vkDeviceWaitIdle(device);
		vkDestroyCommandPool(device, commandPool, null);
		vkDestroyDevice(device, null);
		vkDestroyInstance(instance, null);
		memoryProperties.free();
	}
}
