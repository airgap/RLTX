package rltx.sky;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;

/**
 * Decodes a skybox PNG into an equirectangular RGBA8 map. Panoramas are used as-is;
 * horizontal-cross cubemaps are resampled onto an equirectangular grid.
 */
public final class SkyboxLoader
{
	/** Decoded sky. The caller owns {@code pixels} and frees it with {@link MemoryUtil#memFree}. */
	public static final class Decoded
	{
		public final int width;
		public final int height;
		public final ByteBuffer pixels;
		/**
		 * Compass azimuth of the painted sun or moon in the image, clockwise from the image
		 * centre, or NaN when no bright body stands out.
		 */
		public final double sunAzimuthDegrees;
		/** Contrast of the detected body against its surroundings, 0 to 255; diagnostic. */
		public final double sunScore;
		/** Mean colour just above the horizon, RGB 0 to 1, which distance fog fades scenery into. */
		public final float[] horizon;

		Decoded(int width, int height, ByteBuffer pixels)
		{
			this.width = width;
			this.height = height;
			this.pixels = pixels;
			double[] sun = findSun(pixels, width, height);
			this.sunScore = sun[1];
			this.sunAzimuthDegrees = sun[0];
			this.horizon = horizonColor(pixels, width, height);
		}
	}

	private static float[] horizonColor(ByteBuffer pixels, int width, int height)
	{
		int y0 = (int) (height * 0.46);
		int y1 = (int) (height * 0.5);
		double r = 0, g = 0, b = 0;
		long n = 0;
		for (int y = y0; y < y1; ++y)
		{
			for (int x = 0; x < width; x += 4)
			{
				int i = (y * width + x) * 4;
				r += pixels.get(i) & 0xff;
				g += pixels.get(i + 1) & 0xff;
				b += pixels.get(i + 2) & 0xff;
				++n;
			}
		}
		return new float[]{(float) (r / n / 255.0), (float) (g / n / 255.0), (float) (b / n / 255.0)};
	}

	// The sun or moon is a compact bright peak standing out against the sky around it, whereas
	// clouds are broad. Each cell of a coarse luminance grid is scored by its excess over the
	// mean of a ring of cells around it; the best peak wins if it is bright and distinct enough.
	private static double[] findSun(ByteBuffer pixels, int width, int height)
	{
		int cell = Math.max(width / 256, 1);
		int cols = width / cell;
		int rows = (int) (height * 0.6) / cell;
		float[] grid = new float[cols * rows];
		for (int cy = 0; cy < rows; ++cy)
		{
			for (int cx = 0; cx < cols; ++cx)
			{
				double sum = 0;
				for (int y = cy * cell; y < (cy + 1) * cell; ++y)
				{
					for (int x = cx * cell; x < (cx + 1) * cell; ++x)
					{
						int i = (y * width + x) * 4;
						sum += luminance(pixels, i);
					}
				}
				grid[cy * cols + cx] = (float) (sum / (cell * cell));
			}
		}

		int ringInner = 5;
		int ringOuter = 8;
		double bestScore = 0;
		int bestX = -1;
		for (int cy = 0; cy < rows; ++cy)
		{
			for (int cx = 0; cx < cols; ++cx)
			{
				float centre = grid[cy * cols + cx];
				if (centre < 180)
				{
					continue;
				}
				double ring = 0;
				int count = 0;
				for (int dy = -ringOuter; dy <= ringOuter; ++dy)
				{
					int y = cy + dy;
					if (y < 0 || y >= rows)
					{
						continue;
					}
					for (int dx = -ringOuter; dx <= ringOuter; ++dx)
					{
						if (Math.max(Math.abs(dx), Math.abs(dy)) < ringInner)
						{
							continue;
						}
						// Azimuth wraps around the panorama.
						int x = ((cx + dx) % cols + cols) % cols;
						ring += grid[y * cols + x];
						++count;
					}
				}
				double score = centre - ring / Math.max(count, 1);
				if (score > bestScore)
				{
					bestScore = score;
					bestX = cx;
				}
			}
		}
		if (bestX < 0 || bestScore < SUN_MIN_SCORE)
		{
			return new double[]{Double.NaN, bestScore};
		}
		return new double[]{((bestX + 0.5) / cols - 0.5) * 360.0, bestScore};
	}

	private static final double SUN_MIN_SCORE = 60;
	private static final double TWIN_MIN_DIFFERENCE = 40;

	/**
	 * Azimuth of the sun or moon as the spot where a sky differs most from its sunless or
	 * moonless twin, or NaN when the two are the same size or barely differ.
	 */
	public static double sunByDifference(Decoded lit, Decoded unlit)
	{
		if (lit.width != unlit.width || lit.height != unlit.height)
		{
			return Double.NaN;
		}
		int cell = Math.max(lit.width / 256, 1);
		int cols = lit.width / cell;
		int rows = (int) (lit.height * 0.6) / cell;
		double best = 0;
		int bestX = -1;
		for (int cy = 0; cy < rows; ++cy)
		{
			for (int cx = 0; cx < cols; ++cx)
			{
				double diff = 0;
				for (int y = cy * cell; y < (cy + 1) * cell; ++y)
				{
					for (int x = cx * cell; x < (cx + 1) * cell; ++x)
					{
						int i = (y * lit.width + x) * 4;
						diff += luminance(lit.pixels, i) - luminance(unlit.pixels, i);
					}
				}
				diff /= cell * cell;
				if (diff > best)
				{
					best = diff;
					bestX = cx;
				}
			}
		}
		if (bestX < 0 || best < TWIN_MIN_DIFFERENCE)
		{
			return Double.NaN;
		}
		return ((bestX + 0.5) / cols - 0.5) * 360.0;
	}

	private static double luminance(ByteBuffer pixels, int i)
	{
		return 0.2126 * (pixels.get(i) & 0xff) + 0.7152 * (pixels.get(i + 1) & 0xff) + 0.0722 * (pixels.get(i + 2) & 0xff);
	}

	private SkyboxLoader()
	{
	}

	public static Decoded load(Path file) throws IOException
	{
		BufferedImage image = ImageIO.read(file.toFile());
		if (image == null)
		{
			throw new IOException("Not an image: " + file);
		}
		int w = image.getWidth();
		int h = image.getHeight();
		int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
		if (w == 2 * h)
		{
			return pack(argb, w, h);
		}
		if (w * 3 == h * 4)
		{
			return crossToEquirect(argb, w, h);
		}
		throw new IOException("Unsupported skybox layout " + w + "x" + h + " in " + file + " (need 2:1 panorama or 4:3 cross)");
	}

	private static Decoded pack(int[] argb, int w, int h)
	{
		ByteBuffer out = MemoryUtil.memAlloc(w * h * 4);
		for (int p : argb)
		{
			out.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) 0xff);
		}
		out.flip();
		return new Decoded(w, h, out);
	}

	// Cross layout, 4 columns by 3 rows, as seen from inside the cube:
	//            [ up  ]
	//  [left][front][right][back]
	//            [down]
	// The front face looks north (+z), right is east (+x); the up face's bottom edge meets the front face.
	private static Decoded crossToEquirect(int[] argb, int w, int h)
	{
		int face = w / 4;
		int outW = face * 4;
		int outH = face * 2;
		ByteBuffer out = MemoryUtil.memAlloc(outW * outH * 4);
		for (int j = 0; j < outH; ++j)
		{
			double theta = (j + 0.5) / outH * Math.PI;
			double up = Math.cos(theta);
			double r = Math.sin(theta);
			for (int i = 0; i < outW; ++i)
			{
				double phi = ((i + 0.5) / outW - 0.5) * 2.0 * Math.PI;
				double x = r * Math.sin(phi);
				double z = r * Math.cos(phi);
				int rgb = sampleCross(argb, w, face, x, up, z);
				out.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) 0xff);
			}
		}
		out.flip();
		return new Decoded(outW, outH, out);
	}

	private static int sampleCross(int[] argb, int stride, int face, double x, double up, double z)
	{
		double ax = Math.abs(x), ay = Math.abs(up), az = Math.abs(z);
		int col, row;
		double s, t;
		if (ay >= ax && ay >= az)
		{
			if (up > 0)
			{
				col = 1;
				row = 0;
				s = x / ay;
				t = z / ay;
			}
			else
			{
				col = 1;
				row = 2;
				s = x / ay;
				t = -z / ay;
			}
		}
		else if (az >= ax)
		{
			if (z > 0)
			{
				col = 1;
				row = 1;
				s = x / az;
				t = -up / az;
			}
			else
			{
				col = 3;
				row = 1;
				s = -x / az;
				t = -up / az;
			}
		}
		else if (x > 0)
		{
			col = 2;
			row = 1;
			s = -z / ax;
			t = -up / ax;
		}
		else
		{
			col = 0;
			row = 1;
			s = z / ax;
			t = -up / ax;
		}

		double fx = col * face + (s * 0.5 + 0.5) * (face - 1);
		double fy = row * face + (t * 0.5 + 0.5) * (face - 1);
		return bilinear(argb, stride, fx, fy, col * face, row * face, face);
	}

	private static int bilinear(int[] argb, int stride, double fx, double fy, int x0, int y0, int size)
	{
		int ix = (int) Math.floor(fx);
		int iy = (int) Math.floor(fy);
		double ax = fx - ix;
		double ay = fy - iy;
		int ix1 = Math.min(ix + 1, x0 + size - 1);
		int iy1 = Math.min(iy + 1, y0 + size - 1);
		int c00 = argb[iy * stride + ix];
		int c10 = argb[iy * stride + ix1];
		int c01 = argb[iy1 * stride + ix];
		int c11 = argb[iy1 * stride + ix1];
		int result = 0;
		for (int shift = 0; shift <= 16; shift += 8)
		{
			double v = ((c00 >> shift & 0xff) * (1 - ax) + (c10 >> shift & 0xff) * ax) * (1 - ay)
				+ ((c01 >> shift & 0xff) * (1 - ax) + (c11 >> shift & 0xff) * ax) * ay;
			result |= ((int) Math.round(v) & 0xff) << shift;
		}
		return result;
	}
}
