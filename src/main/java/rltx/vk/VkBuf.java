package rltx.vk;

import java.nio.ByteBuffer;

public final class VkBuf
{
	public final long buffer;
	public final long memory;
	public final long size;
	/** Shader device address, or 0 when the buffer was created without that usage. */
	public final long address;
	/** Persistent mapping for host-visible buffers, otherwise null. */
	public final ByteBuffer mapped;

	VkBuf(long buffer, long memory, long size, long address, ByteBuffer mapped)
	{
		this.buffer = buffer;
		this.memory = memory;
		this.size = size;
		this.address = address;
		this.mapped = mapped;
	}
}
