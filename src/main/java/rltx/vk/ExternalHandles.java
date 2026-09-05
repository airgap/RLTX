package rltx.vk;

import static org.lwjgl.vulkan.KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.vkGetMemoryFdKHR;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR;
import static org.lwjgl.vulkan.VK11.*;
import static rltx.vk.VkUtil.check;

import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryGetFdInfoKHR;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreGetFdInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreGetWin32HandleInfoKHR;

/**
 * How Vulkan hands its output image and semaphores to OpenGL: opaque file descriptors on Linux,
 * opaque NT handles on Windows. Either way the OpenGL import takes ownership of the handle.
 */
public final class ExternalHandles
{
	public static final boolean WIN32 = Platform.get() == Platform.WINDOWS;
	public static final String[] DEVICE_EXTENSIONS = WIN32
		? new String[]{VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME}
		: new String[]{VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME};
	public static final int MEMORY_HANDLE_TYPE = WIN32 ? VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT : VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
	public static final int SEMAPHORE_HANDLE_TYPE = WIN32 ? VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT : VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;

	private ExternalHandles()
	{
	}

	static long exportMemory(VkDevice device, MemoryStack stack, long memory)
	{
		if (WIN32)
		{
			VkMemoryGetWin32HandleInfoKHR info = VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default()
				.memory(memory)
				.handleType(MEMORY_HANDLE_TYPE);
			PointerBuffer pHandle = stack.mallocPointer(1);
			check(vkGetMemoryWin32HandleKHR(device, info, pHandle), "vkGetMemoryWin32HandleKHR");
			return pHandle.get(0);
		}
		VkMemoryGetFdInfoKHR info = VkMemoryGetFdInfoKHR.calloc(stack).sType$Default()
			.memory(memory)
			.handleType(MEMORY_HANDLE_TYPE);
		IntBuffer pFd = stack.mallocInt(1);
		check(vkGetMemoryFdKHR(device, info, pFd), "vkGetMemoryFdKHR");
		return pFd.get(0);
	}

	static long exportSemaphore(VkDevice device, MemoryStack stack, long semaphore)
	{
		if (WIN32)
		{
			VkSemaphoreGetWin32HandleInfoKHR info = VkSemaphoreGetWin32HandleInfoKHR.calloc(stack).sType$Default()
				.semaphore(semaphore)
				.handleType(SEMAPHORE_HANDLE_TYPE);
			PointerBuffer pHandle = stack.mallocPointer(1);
			check(vkGetSemaphoreWin32HandleKHR(device, info, pHandle), "vkGetSemaphoreWin32HandleKHR");
			return pHandle.get(0);
		}
		VkSemaphoreGetFdInfoKHR info = VkSemaphoreGetFdInfoKHR.calloc(stack).sType$Default()
			.semaphore(semaphore)
			.handleType(SEMAPHORE_HANDLE_TYPE);
		IntBuffer pFd = stack.mallocInt(1);
		check(vkGetSemaphoreFdKHR(device, info, pFd), "vkGetSemaphoreFdKHR");
		return pFd.get(0);
	}
}
