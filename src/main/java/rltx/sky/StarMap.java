package rltx.sky;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * The night sky as an equirectangular image in equatorial coordinates, right ascension along x
 * and declination down y: the Bright Star Catalogue's stars as soft points sized and coloured by
 * their brightness and colour index, over a faint Milky Way along the galactic plane.
 */
public final class StarMap
{
	public static final int WIDTH = 4096;
	public static final int HEIGHT = 2048;

	// Galactic north pole and centre, J2000.
	private static final double POLE_RA = Math.toRadians(192.8595);
	private static final double POLE_DEC = Math.toRadians(27.1283);
	private static final double CENTRE_RA = Math.toRadians(266.405);
	private static final double CENTRE_DEC = Math.toRadians(-28.936);

	private StarMap()
	{
	}

	/** Renders the map; the caller owns the returned buffer and frees it with {@code MemoryUtil.memFree}. */
	public static ByteBuffer render()
	{
		float[] stars = load();
		ByteBuffer out = MemoryUtil.memCalloc(WIDTH * HEIGHT * 4);
		milkyWay(out);
		for (int i = 0; i < stars.length; i += 4)
		{
			star(out, stars[i], stars[i + 1], stars[i + 2], stars[i + 3]);
		}
		return out;
	}

	// Right ascension, declination, visual magnitude and B-V colour per star, brightest first.
	private static float[] load()
	{
		try (InputStream in = StarMap.class.getResourceAsStream("/rltx/stars.bin"))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing /rltx/stars.bin");
			}
			byte[] bytes = in.readAllBytes();
			ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			int count = b.getInt();
			float[] stars = new float[count * 4];
			b.asFloatBuffer().get(stars);
			return stars;
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private static void milkyWay(ByteBuffer out)
	{
		double[] pole = unit(POLE_RA, POLE_DEC);
		double[] centre = unit(CENTRE_RA, CENTRE_DEC);
		for (int y = 0; y < HEIGHT; ++y)
		{
			double dec = Math.toRadians(90.0 - (y + 0.5) * 180.0 / HEIGHT);
			for (int x = 0; x < WIDTH; ++x)
			{
				double ra = Math.toRadians((x + 0.5) * 360.0 / WIDTH);
				double[] e = unit(ra, dec);
				double latitude = Math.asin(clamp(e[0] * pole[0] + e[1] * pole[1] + e[2] * pole[2]));
				double fromCentre = Math.acos(clamp(e[0] * centre[0] + e[1] * centre[1] + e[2] * centre[2]));
				// Brightest toward the galactic centre, with a mottling so the band is not a smooth ribbon.
				double band = Math.exp(-Math.pow(latitude / Math.toRadians(7.0), 2.0)) * (0.45 + 0.55 * Math.exp(-Math.pow(fromCentre / Math.toRadians(70.0), 2.0)));
				double mottle = 0.7 + 0.3 * Math.sin(ra * 23.0 + Math.sin(dec * 17.0) * 3.0) * Math.cos(dec * 31.0 + ra * 5.0);
				double glow = 0.022 * band * mottle;
				if (glow > 0.0005)
				{
					add(out, x, y, glow * 0.78, glow * 0.82, glow * 0.95);
				}
			}
		}
	}

	private static void star(ByteBuffer out, float raDegrees, float decDegrees, float magnitude, float colourIndex)
	{
		if (magnitude > 6.8f)
		{
			return;
		}
		double flux = Math.pow(10.0, -0.4 * (magnitude - 1.0));
		double amplitude = flux * 1.4;
		double sigma = 0.75 + 0.55 * Math.max(0.0, Math.log10(flux));
		int radius = (int) Math.ceil(3.0 * sigma);
		double cx = raDegrees / 360.0 * WIDTH;
		double cy = (90.0 - decDegrees) / 180.0 * HEIGHT;
		// Rows near the poles are stretched across the width, so the kernel stretches with them.
		double stretch = Math.min(8.0, 1.0 / Math.max(0.05, Math.cos(Math.toRadians(decDegrees))));
		double[] tint = colour(colourIndex);
		int x0 = (int) Math.floor(cx - radius * stretch);
		int x1 = (int) Math.ceil(cx + radius * stretch);
		int y0 = Math.max(0, (int) Math.floor(cy - radius));
		int y1 = Math.min(HEIGHT - 1, (int) Math.ceil(cy + radius));
		for (int y = y0; y <= y1; ++y)
		{
			double dy = y + 0.5 - cy;
			for (int x = x0; x <= x1; ++x)
			{
				double dx = (x + 0.5 - cx) / stretch;
				double value = amplitude * Math.exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma));
				if (value > 0.002)
				{
					add(out, ((x % WIDTH) + WIDTH) % WIDTH, y, value * tint[0], value * tint[1], value * tint[2]);
				}
			}
		}
	}

	// Star colour by B-V index: blue-white for the hottest through white to orange.
	private static final double[] BV = {-0.3, 0.0, 0.3, 0.6, 0.9, 1.3, 1.8};
	private static final double[][] TINT = {
		{0.67, 0.78, 1.0}, {0.80, 0.87, 1.0}, {0.93, 0.95, 1.0}, {1.0, 0.97, 0.90}, {1.0, 0.90, 0.75}, {1.0, 0.80, 0.58}, {1.0, 0.68, 0.42}
	};

	private static double[] colour(double bv)
	{
		if (bv <= BV[0])
		{
			return TINT[0];
		}
		for (int i = 1; i < BV.length; ++i)
		{
			if (bv <= BV[i])
			{
				double f = (bv - BV[i - 1]) / (BV[i] - BV[i - 1]);
				return new double[]{
					TINT[i - 1][0] + (TINT[i][0] - TINT[i - 1][0]) * f,
					TINT[i - 1][1] + (TINT[i][1] - TINT[i - 1][1]) * f,
					TINT[i - 1][2] + (TINT[i][2] - TINT[i - 1][2]) * f};
			}
		}
		return TINT[TINT.length - 1];
	}

	private static void add(ByteBuffer out, int x, int y, double r, double g, double b)
	{
		int o = (y * WIDTH + x) * 4;
		out.put(o, (byte) Math.min(255, (out.get(o) & 0xff) + (int) Math.round(r * 255.0)));
		out.put(o + 1, (byte) Math.min(255, (out.get(o + 1) & 0xff) + (int) Math.round(g * 255.0)));
		out.put(o + 2, (byte) Math.min(255, (out.get(o + 2) & 0xff) + (int) Math.round(b * 255.0)));
		out.put(o + 3, (byte) 255);
	}

	private static double[] unit(double ra, double dec)
	{
		return new double[]{Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)};
	}

	private static double clamp(double v)
	{
		return Math.max(-1.0, Math.min(1.0, v));
	}
}
