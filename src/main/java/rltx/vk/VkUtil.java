package rltx.vk;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

final class VkUtil
{
	private VkUtil()
	{
	}

	static void check(int result, String what)
	{
		if (result != VK_SUCCESS)
		{
			throw new IllegalStateException(what + " failed with VkResult " + result);
		}
	}

	static long alignUp(long value, long alignment)
	{
		return (value + alignment - 1) / alignment * alignment;
	}
}
