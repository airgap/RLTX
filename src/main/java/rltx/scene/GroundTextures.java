package rltx.scene;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;

/**
 * Detail textures for untextured terrain, taken from 117 HD's texture pack (licences in the
 * bundled LICENSES.txt). A tile keeps its vanilla colour; the texture supplies grain and a
 * normal map, chosen by the colour's hue and lightness. The layers sit above the vanilla
 * texture ids in the same array: colours first, then the normal maps.
 */
public final class GroundTextures
{
	public enum Kind
	{
		GRASS("grass_1.jpg", null, 0f, 0f),
		DIRT("dirt_1.jpg", "dirt_1_n.png", 0.13f, 18f),
		SAND("sand_1.jpg", "sand_1_n.png", 0.2f, 10f),
		ROCK("rock_1.jpg", "rock_1_n.png", 0.3f, 40f),
		GRAVEL("gravel.jpg", "gravel_n.png", 0.4f, 130f),
		SNOW("snow_1.jpg", "snow_1_n.png", 0.4f, 20f);

		final String colorFile;
		final String normalFile;
		final float specularStrength;
		final float specularGloss;

		Kind(String colorFile, String normalFile, float specularStrength, float specularGloss)
		{
			this.colorFile = colorFile;
			this.normalFile = normalFile;
			this.specularStrength = specularStrength;
			this.specularGloss = specularGloss;
		}

		/** Texture layer of the colour detail, also the texture id written per face. */
		public int layer()
		{
			return BASE + ordinal();
		}

		/** Texture layer of the normal map, or -1 without one. */
		public int normalLayer()
		{
			return normalFile == null ? -1 : BASE + values().length + ordinal();
		}
	}

	/** First layer used, above every vanilla texture id. */
	public static final int BASE = 256;

	private GroundTextures()
	{
	}

	public static int layerCount()
	{
		return 2 * Kind.values().length;
	}

	/** Which detail suits a face colour, given as the buffer's 0xAABBGGRR packing. */
	public static Kind classify(int rgba)
	{
		float r = (rgba & 0xff) / 255f;
		float g = (rgba >> 8 & 0xff) / 255f;
		float b = (rgba >> 16 & 0xff) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float light = (max + min) * 0.5f;
		float sat = max == min ? 0f : (max - min) / (1f - Math.abs(2f * light - 1f));
		if (sat < 0.12f)
		{
			return light > 0.72f ? Kind.SNOW : light > 0.35f ? Kind.GRAVEL : Kind.ROCK;
		}
		float hue;
		if (max == r)
		{
			hue = 60f * (((g - b) / (max - min)) % 6f);
		}
		else if (max == g)
		{
			hue = 60f * ((b - r) / (max - min) + 2f);
		}
		else
		{
			hue = 60f * ((r - g) / (max - min) + 4f);
		}
		if (hue < 0f)
		{
			hue += 360f;
		}
		if (hue >= 70f && hue < 170f)
		{
			return Kind.GRASS;
		}
		if (hue >= 25f && hue < 70f)
		{
			return light > 0.45f ? Kind.SAND : Kind.DIRT;
		}
		if (hue < 25f || hue >= 330f)
		{
			return Kind.DIRT;
		}
		return Kind.ROCK;
	}

	/**
	 * Decodes every texture into the array at its layer. Colour maps are stored as detail, the
	 * texel over the map's mean luminance at half scale, so the shader doubles them and
	 * multiplies the tile colour; normal maps are stored as they are.
	 */
	public static void pack(ByteBuffer packed, int size)
	{
		for (Kind kind : Kind.values())
		{
			int[] color = resample(read(kind.colorFile), size);
			double mean = 0;
			for (int p : color)
			{
				mean += 0.2126 * (p >> 16 & 0xff) + 0.7152 * (p >> 8 & 0xff) + 0.0722 * (p & 0xff);
			}
			mean = Math.max(mean / color.length, 1.0);
			int base = kind.layer() * size * size * 4;
			for (int i = 0; i < color.length; ++i)
			{
				int p = color[i];
				int o = base + i * 4;
				packed.put(o, detail(p >> 16 & 0xff, mean)).put(o + 1, detail(p >> 8 & 0xff, mean)).put(o + 2, detail(p & 0xff, mean)).put(o + 3, (byte) 0xff);
			}
			if (kind.normalFile != null)
			{
				int[] normal = resample(read(kind.normalFile), size);
				int nbase = kind.normalLayer() * size * size * 4;
				for (int i = 0; i < normal.length; ++i)
				{
					int p = normal[i];
					int o = nbase + i * 4;
					packed.put(o, (byte) (p >> 16)).put(o + 1, (byte) (p >> 8)).put(o + 2, (byte) p).put(o + 3, (byte) 0xff);
				}
			}
		}
	}

	private static int lerp(int a, int b, float t)
	{
		int r = Math.round((a >> 16 & 0xff) + ((b >> 16 & 0xff) - (a >> 16 & 0xff)) * t);
		int g = Math.round((a >> 8 & 0xff) + ((b >> 8 & 0xff) - (a >> 8 & 0xff)) * t);
		int bl = Math.round((a & 0xff) + ((b & 0xff) - (a & 0xff)) * t);
		return r << 16 | g << 8 | bl;
	}

	private static byte detail(int channel, double mean)
	{
		return (byte) Math.min(255, Math.round(channel / mean * 127.5));
	}

	/** Specular strength and gloss from 117 HD's materials, and the normal map layer plus one. */
	public static void applyMaterials(float[] table)
	{
		for (Kind kind : Kind.values())
		{
			int base = kind.layer() * Materials.FLOATS;
			table[base] = kind.specularStrength;
			table[base + 1] = kind.specularGloss;
			table[base + 2] = 0f;
			table[base + 3] = kind.normalLayer() < 0 ? 0f : kind.normalLayer() + 1;
		}
	}

	private static BufferedImage read(String file)
	{
		try (InputStream in = GroundTextures.class.getResourceAsStream("/rltx/hd/ground/" + file))
		{
			if (in == null)
			{
				throw new IOException("Missing bundled ground texture " + file);
			}
			return ImageIO.read(in);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	// Box filter down to the array's size; the pack's textures are 256 or 512 square.
	// Larger sources are averaged down in blocks; smaller ones are enlarged with bilinear samples
	// that wrap, since the textures tile.
	private static int[] resample(BufferedImage image, int size)
	{
		int w = image.getWidth();
		int h = image.getHeight();
		int[] src = image.getRGB(0, 0, w, h, null, 0, w);
		int[] out = new int[size * size];
		if (w < size || h < size)
		{
			for (int y = 0; y < size; ++y)
			{
				float fy = (y + 0.5f) * h / size - 0.5f;
				int y0 = (int) Math.floor(fy);
				float ty = fy - y0;
				int ya = ((y0 % h) + h) % h;
				int yb = (ya + 1) % h;
				for (int x = 0; x < size; ++x)
				{
					float fx = (x + 0.5f) * w / size - 0.5f;
					int x0 = (int) Math.floor(fx);
					float tx = fx - x0;
					int xa = ((x0 % w) + w) % w;
					int xb = (xa + 1) % w;
					out[y * size + x] = lerp(lerp(src[ya * w + xa], src[ya * w + xb], tx), lerp(src[yb * w + xa], src[yb * w + xb], tx), ty);
				}
			}
			return out;
		}
		int bx = Math.max(w / size, 1);
		int by = Math.max(h / size, 1);
		for (int y = 0; y < size; ++y)
		{
			for (int x = 0; x < size; ++x)
			{
				int r = 0, g = 0, b = 0, n = 0;
				for (int yy = 0; yy < by; ++yy)
				{
					for (int xx = 0; xx < bx; ++xx)
					{
						int sx = Math.min(x * bx + xx, w - 1);
						int sy = Math.min(y * by + yy, h - 1);
						int p = src[sy * w + sx];
						r += p >> 16 & 0xff;
						g += p >> 8 & 0xff;
						b += p & 0xff;
						++n;
					}
				}
				out[y * size + x] = (r / n) << 16 | (g / n) << 8 | (b / n);
			}
		}
		return out;
	}
}
