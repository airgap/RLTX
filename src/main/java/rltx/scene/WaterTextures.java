package rltx.scene;

/**
 * Vanilla texture ids that represent a water surface, following 117 HD's rule, and the
 * per-face encoding of texture and water type.
 */
public final class WaterTextures
{
	private WaterTextures()
	{
	}

	public static boolean isWater(int textureId)
	{
		return textureId >= 0 && WaterType.forTexture(textureId) != null;
	}

	/**
	 * Texture id plus one in the low 16 bits; for water, the water type index in bits 16 to 23
	 * and the top bit set.
	 */
	public static int encode(int textureId)
	{
		WaterType type = textureId >= 0 ? WaterType.forTexture(textureId) : null;
		int field = textureId + 1;
		if (type != null)
		{
			field |= (type.ordinal() + 1) << 16 | WATER_BIT;
		}
		return field;
	}

	public static final int WATER_BIT = 1 << 31;
}
