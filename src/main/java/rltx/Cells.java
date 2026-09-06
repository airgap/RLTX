package rltx;

import java.util.Arrays;
import rltx.vk.RtRenderer;

/**
 * Occupancy of the scene by routes, markers and footprints, a bit per 512-unit cell, so the
 * shaders' per-pixel loops over them run only where they can matter. Cells start at -6144.
 */
final class Cells
{
	static final int ROUTE = 0;
	static final int MARKERS = 1;
	static final int PRINTS = 2;

	final int[] bits = new int[RtRenderer.CELL_LAYERS * RtRenderer.CELL_WORDS];

	void clear(int layer)
	{
		Arrays.fill(bits, layer * RtRenderer.CELL_WORDS, (layer + 1) * RtRenderer.CELL_WORDS, 0);
	}

	void mark(int layer, float minX, float minZ, float maxX, float maxZ)
	{
		int x0 = Math.max(0, (int) Math.floor((minX + 6144f) / 512f));
		int x1 = Math.min(63, (int) Math.floor((maxX + 6144f) / 512f));
		int z0 = Math.max(0, (int) Math.floor((minZ + 6144f) / 512f));
		int z1 = Math.min(63, (int) Math.floor((maxZ + 6144f) / 512f));
		for (int x = x0; x <= x1; ++x)
		{
			for (int z = z0; z <= z1; ++z)
			{
				int bit = x * 64 + z;
				bits[layer * RtRenderer.CELL_WORDS + (bit >> 5)] |= 1 << (bit & 31);
			}
		}
	}
}
