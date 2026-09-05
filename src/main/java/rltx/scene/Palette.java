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
	/** Whether the vanilla renderer's baked shading is reversed out of face and tile colours. */
	public final boolean undoShading;
	/** The season the scene is coloured for: 0 none, 1 spring, 2 summer, 3 autumn, 4 winter; and how far through it. */
	public final int season;
	public final float seasonProgress;

	public Palette(TextureProvider textureProvider, boolean undoShading, int season, float seasonProgress)
	{
		this.textureProvider = textureProvider;
		this.brightness = textureProvider.getBrightness();
		this.undoShading = undoShading;
		this.season = season;
		this.seasonProgress = seasonProgress;
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

	/**
	 * The season's version of a green: leaves turn and fall through autumn on their own
	 * schedules, stand bare and brown in winter, and come up fresh with blossom in spring; the
	 * ground follows more gently. Returns -1 for a leaf that has fallen.
	 *
	 * @param salt varies the schedule between faces
	 */
	public int seasonal(int hsl, boolean foliage, int salt)
	{
		if (season == 0 || season == 2)
		{
			return hsl;
		}
		int hue = hsl >> 10 & 63;
		int sat = hsl >> 7 & 7;
		int light = hsl & 127;
		if (hue < 11 || hue > 29 || sat < 2 || light < 8)
		{
			return hsl;
		}
		float r = ((salt * 0x9E3779B1) >>> 8 & 0xFFFF) / 65536f;
		float p = seasonProgress;
		switch (season)
		{
			case 3:
			{
				float turn = Math.max(0f, Math.min(1f, (p * 1.5f - 0.5f * r) * (foliage ? 1f : 0.7f)));
				if (foliage && p > 0.7f && r < (p - 0.7f) / 0.3f * 0.6f)
				{
					return -1;
				}
				hue = Math.round(hue + (9f - 6f * turn - hue) * turn);
				sat = turn > 0.3f ? Math.min(7, sat + 1) : sat;
				light = Math.round(light * (1f - 0.3f * turn * turn));
				break;
			}
			case 4:
				if (foliage)
				{
					if (r < 0.6f)
					{
						return -1;
					}
					hue = 4;
					sat = Math.max(sat - 2, 1);
					light = light * 3 / 4;
				}
				else
				{
					sat = Math.max(sat - 1, 0);
					light = Math.min(127, light + 8);
				}
				break;
			default:
				if (foliage)
				{
					float bloom = 1f - Math.abs(p - 0.45f) * 2.5f;
					if (bloom > 0f && r < 0.14f * bloom)
					{
						hue = 60;
						sat = 3;
						light = 90;
					}
					else
					{
						light = Math.min(127, Math.round(light + 6f * (1f - p)));
					}
				}
				break;
		}
		return hue << 10 | sat << 7 | light;
	}

	private static final int BASE_LIGHTEN = 10;
	private static final int IGNORE_LOW_LIGHTNESS = 3;
	private static final float LIGHTNESS_MULTIPLIER = 3f;
	private static final int[] MAX_BRIGHTNESS = {127, 61, 59, 57, 56, 56, 55, 55};

	/**
	 * Approximately reverses the vanilla per-face shading of a model colour from its normal,
	 * following 117 HD: faces the vanilla light darkened are brightened back, capped so
	 * saturated colours do not blow out.
	 */
	public static int undoModelShading(int hsl, float nx, float ny, float nz)
	{
		int s = (hsl >> 7) & 7;
		int l = hsl & 127;
		float adjust = BASE_LIGHTEN - l + (l < IGNORE_LOW_LIGHTNESS ? 0f : (l - IGNORE_LOW_LIGHTNESS) * LIGHTNESS_MULTIPLIER);
		float len = nx * nx + ny * ny + nz * nz;
		if (len > 0f)
		{
			float lightDotNormal = (nx + ny + nz) * 0.57735026f / (float) Math.sqrt(len);
			if (lightDotNormal > 0f)
			{
				l += (int) (lightDotNormal * adjust);
			}
		}
		l = Math.min(l, MAX_BRIGHTNESS[s]);
		return (hsl & 0xFC00) | (s << 7) | l;
	}

	/**
	 * Reverses the vanilla terrain lighting of a tile colour given the light value the client
	 * computed for that grid point, which scales lightness by light/128.
	 */
	public static int undoTerrainShading(int hsl, int light)
	{
		int l = hsl & 127;
		l = Math.max(0, Math.min(127, l * 128 / Math.max(light, 1)));
		return (hsl & 0xFF80) | l;
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
