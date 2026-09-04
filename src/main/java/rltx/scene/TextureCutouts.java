package rltx.scene;

import java.util.BitSet;

/**
 * Which game textures contain fully transparent texels. Faces using them go through the
 * non-opaque ray query path so the holes can be alpha-tested. Filled in once the client has
 * decoded its textures; until then every texture counts as solid.
 */
public final class TextureCutouts
{
	private static volatile BitSet cutouts = new BitSet();

	private TextureCutouts()
	{
	}

	public static void set(BitSet ids)
	{
		cutouts = ids;
	}

	public static boolean isCutout(int textureId)
	{
		return textureId >= 0 && cutouts.get(textureId);
	}
}
