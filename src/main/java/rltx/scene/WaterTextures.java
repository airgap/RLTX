package rltx.scene;

import java.util.BitSet;

/**
 * Vanilla texture ids that represent a water surface, taken from 117 HD's material list
 * (every WATER_* material except droplets and ice).
 */
public final class WaterTextures
{
	private static final BitSet WATER = new BitSet();

	static
	{
		int[] ids = {1, 24, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 208};
		for (int id : ids)
		{
			WATER.set(id);
		}
	}

	private WaterTextures()
	{
	}

	public static boolean isWater(int textureId)
	{
		return textureId >= 0 && WATER.get(textureId);
	}

	/** Texture id plus one, with the top bit set for water, as stored per face. */
	public static int encode(int textureId)
	{
		return (textureId + 1) | (isWater(textureId) ? WATER_BIT : 0);
	}

	public static final int WATER_BIT = 1 << 31;
}
