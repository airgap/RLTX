package rltx.scene.lights;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.List;

/**
 * One entry of 117 HD's lights.json: a light fixed in the world, or attached to every object
 * or NPC with one of the listed ids. Field names match the file for Gson.
 */
public final class LightDefinition
{
	public enum Type
	{
		STATIC, FLICKER, PULSE
	}

	/** Where a light sits relative to the object it is attached to, as 117 HD defines it. */
	public enum Alignment
	{
		CUSTOM(0, false, true),
		CENTER(0, false, false),
		NORTH(0, true, false),
		NORTHEAST(256, true, false),
		NORTHEAST_CORNER(256, false, false),
		EAST(512, true, false),
		SOUTHEAST(768, true, false),
		SOUTHEAST_CORNER(768, false, false),
		SOUTH(1024, true, false),
		SOUTHWEST(1280, true, false),
		SOUTHWEST_CORNER(1280, false, false),
		WEST(1536, true, false),
		NORTHWEST(1792, true, false),
		NORTHWEST_CORNER(1792, false, false),
		BACK(0, true, true),
		BACKLEFT(256, true, true),
		BACKLEFT_CORNER(256, false, true),
		LEFT(512, true, true),
		FRONTLEFT(768, true, true),
		FRONTLEFT_CORNER(768, false, true),
		FRONT(1024, true, true),
		FRONTRIGHT(1280, true, true),
		FRONTRIGHT_CORNER(1280, false, true),
		RIGHT(1536, true, true),
		BACKRIGHT(1792, true, true),
		BACKRIGHT_CORNER(1792, false, true);

		public final int orientation;
		public final boolean radial;
		public final boolean relative;

		Alignment(int orientation, boolean radial, boolean relative)
		{
			this.orientation = orientation;
			this.radial = radial;
			this.relative = relative;
		}
	}

	public String description;
	public Integer worldX;
	public Integer worldY;
	public int plane;
	public int height;
	public Alignment alignment = Alignment.CENTER;
	public float radius;
	public float strength;
	/** Either three numbers 0 to 255 or a hex string such as "#91c7ff". */
	public JsonElement color;
	public Type type = Type.STATIC;
	public float duration;
	public float range;
	public float[] offset;
	/** Object names from 117 HD's game value table, or plain ids. */
	public List<String> objectIds;
	public List<String> npcIds;

	public boolean fixed()
	{
		return worldX != null && worldY != null;
	}

	/** The colour as RGB 0 to 1. */
	public float[] rgb()
	{
		if (color == null)
		{
			return new float[]{1f, 1f, 1f};
		}
		if (color.isJsonArray())
		{
			JsonArray a = color.getAsJsonArray();
			return new float[]{a.get(0).getAsFloat() / 255f, a.get(1).getAsFloat() / 255f, a.get(2).getAsFloat() / 255f};
		}
		int hex = Integer.parseInt(color.getAsString().replace("#", ""), 16);
		return new float[]{(hex >> 16 & 0xff) / 255f, (hex >> 8 & 0xff) / 255f, (hex & 0xff) / 255f};
	}

	/**
	 * Offset of the light from its object's centre in scene units, following 117 HD: radial
	 * alignments sit on the object's edge in the given direction, corners on its diagonal, and
	 * CUSTOM uses the explicit offset turned by the object's orientation.
	 *
	 * @param orientation the object's model orientation in 2048ths of a turn
	 */
	public void placement(int orientation, int sizeX, int sizeY, float[] out)
	{
		int turn = alignment.relative ? (orientation + alignment.orientation) & 2047 : alignment.orientation;
		double angle = turn * (2.0 * Math.PI / 2048.0);
		if (alignment == Alignment.CUSTOM)
		{
			float x = offset == null ? 0f : offset[0];
			float y = offset == null ? 0f : offset[1];
			float z = offset == null ? 0f : offset[2];
			double sin = Math.sin(angle);
			double cos = Math.cos(angle);
			out[0] = (float) (-cos * x - sin * z);
			out[1] = y;
			out[2] = (float) (-cos * z + sin * x);
			return;
		}
		if (alignment == Alignment.CENTER)
		{
			out[0] = 0f;
			out[1] = 0f;
			out[2] = 0f;
			return;
		}
		float localX = sizeX * 128f;
		float localY = sizeY * 128f;
		double radius = alignment.radial ? localX / 2.0 : Math.sqrt(2.0 * localX * localX) / 2.0;
		double sine = Math.sin(angle);
		double cosine = Math.cos(angle) / (localX / localY);
		out[0] = (float) (radius * sine);
		out[1] = 0f;
		out[2] = (float) (radius * cosine);
	}
}
