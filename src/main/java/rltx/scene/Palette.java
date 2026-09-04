package rltx.scene;

import net.runelite.api.TextureProvider;

/**
 * Converts the client's packed 16-bit HSL colors to RGBA8 using the same
 * conversion and brightness gamma as the vanilla renderer.
 */
public final class Palette
{
	private final int[] table = new int[65536];
	private final TextureProvider textureProvider;
	private final double brightness;

	public Palette(TextureProvider textureProvider)
	{
		this.textureProvider = textureProvider;
		this.brightness = textureProvider.getBrightness();
		for (int hsl = 0; hsl < table.length; ++hsl)
		{
			table[hsl] = convert(hsl, brightness);
		}
	}

	public double brightness()
	{
		return brightness;
	}

	public int hsl(int hsl)
	{
		return table[hsl & 0xffff];
	}

	public int texture(int textureId)
	{
		return table[textureProvider.getDefaultColor(textureId) & 0xffff];
	}

	private static int convert(int hsl, double brightness)
	{
		double hue = (hsl >> 10 & 63) / 64.0 + 0.0078125;
		double sat = (hsl >> 7 & 7) / 8.0 + 0.0625;
		double lum = (hsl & 127) / 128.0;

		double q = lum < 0.5 ? lum * (1.0 + sat) : lum + sat - lum * sat;
		double p = 2.0 * lum - q;

		double r = channel(p, q, wrap(hue + 1.0 / 3.0));
		double g = channel(p, q, hue);
		double b = channel(p, q, wrap(hue - 1.0 / 3.0));

		int ri = (int) Math.round(Math.pow(r, brightness) * 255.0);
		int gi = (int) Math.round(Math.pow(g, brightness) * 255.0);
		int bi = (int) Math.round(Math.pow(b, brightness) * 255.0);
		return 0xff000000 | clamp(bi) << 16 | clamp(gi) << 8 | clamp(ri);
	}

	private static double wrap(double h)
	{
		if (h > 1.0)
		{
			return h - 1.0;
		}
		if (h < 0.0)
		{
			return h + 1.0;
		}
		return h;
	}

	private static double channel(double p, double q, double t)
	{
		if (6.0 * t < 1.0)
		{
			return p + (q - p) * 6.0 * t;
		}
		if (2.0 * t < 1.0)
		{
			return q;
		}
		if (3.0 * t < 2.0)
		{
			return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
		}
		return p;
	}

	private static int clamp(int v)
	{
		return v < 0 ? 0 : Math.min(v, 255);
	}
}
