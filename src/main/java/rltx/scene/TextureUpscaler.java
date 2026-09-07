package rltx.scene;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.BitSet;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * The game's textures made larger. Super-xBR, Hyllian's pixel-art scaler, doubles a texture
 * twice with clean edges and nothing invented, so the art stays the art; a texture the player
 * has put in the upscaled folder, from whatever tool they like, is taken instead. The originals
 * are written out once beside it as the starting point for such tools. Every layer gets a chain
 * of mip levels so distant surfaces do not shimmer.
 */
@Slf4j
public final class TextureUpscaler
{
	private static final float WGT1 = 0.129633f;
	private static final float WGT2 = 0.175068f;
	private static final float[] PASS_ONE_WEIGHTS = {2f, 1f, -1f, 4f, -1f, 1f};
	private static final float[] PASS_TWO_WEIGHTS = {2f, 0f, 0f, 0f, 0f, 0f};

	private TextureUpscaler()
	{
	}

	/** How many mip levels a square texture of this size has, down to one texel. */
	public static int levels(int size)
	{
		return 32 - Integer.numberOfLeadingZeros(size);
	}

	/**
	 * Doubles a square ARGB image with Super-xBR. Transparent texels take the colour of their
	 * opaque neighbours first so the blends at cutout edges do not darken; the alpha carries the
	 * coverage.
	 */
	public static int[] superXbr(int[] argb, int size)
	{
		int[] in = dilated(argb, size);
		int w = size;
		int h = size;
		int outW = w * 2;
		int outH = h * 2;
		int[] out = new int[outW * outH];
		float[][] r = new float[4][4], g = new float[4][4], b = new float[4][4], a = new float[4][4], y = new float[4][4];
		float[] result = new float[4];
		// Pass one: the pixel between each two-by-two of originals, from the four-by-four around it.
		for (int cy = 0; cy < h; ++cy)
		{
			for (int cx = 0; cx < w; ++cx)
			{
				for (int sx = -1; sx <= 2; ++sx)
				{
					for (int sy = -1; sy <= 2; ++sy)
					{
						int csy = Math.min(Math.max(sy + cy, 0), h - 1);
						int csx = Math.min(Math.max(sx + cx, 0), w - 1);
						unpack(in[csy * w + csx], r, g, b, a, y, sx + 1, sy + 1);
					}
				}
				blend(r, g, b, a, y, PASS_ONE_WEIGHTS, -WGT1, WGT1 + 0.5f, result);
				out[cy * 2 * outW + cx * 2] = in[cy * w + cx];
				out[(cy * 2 + 1) * outW + cx * 2 + 1] = pack(result);
			}
		}
		// Pass two: the two remaining pixels of each cell, from the diagonal neighbourhood of the first pass.
		for (int cy = 0; cy < h; ++cy)
		{
			for (int cx = 0; cx < w; ++cx)
			{
				int ox = cx * 2;
				int oy = cy * 2;
				for (int sx = -1; sx <= 2; ++sx)
				{
					for (int sy = -1; sy <= 2; ++sy)
					{
						unpack(filled(out, outW, outH, sx + sy + ox, sx - sy + oy), r, g, b, a, y, sx + 1, sy + 1);
					}
				}
				blend(r, g, b, a, y, PASS_TWO_WEIGHTS, -WGT2, WGT2 + 0.5f, result);
				out[oy * outW + ox + 1] = pack(result);
				for (int sx = -1; sx <= 2; ++sx)
				{
					for (int sy = -1; sy <= 2; ++sy)
					{
						unpack(filled(out, outW, outH, sx + sy + ox - 1, sx - sy + oy + 1), r, g, b, a, y, sx + 1, sy + 1);
					}
				}
				blend(r, g, b, a, y, PASS_TWO_WEIGHTS, -WGT2, WGT2 + 0.5f, result);
				out[(oy + 1) * outW + ox] = pack(result);
			}
		}
		// Pass three: every pixel once more against its finished neighbours, in place from the far corner.
		for (int py = outH - 1; py >= 0; --py)
		{
			for (int px = outW - 1; px >= 0; --px)
			{
				for (int sx = -2; sx <= 1; ++sx)
				{
					for (int sy = -2; sy <= 1; ++sy)
					{
						int csy = Math.min(Math.max(sy + py, 0), outH - 1);
						int csx = Math.min(Math.max(sx + px, 0), outW - 1);
						unpack(out[csy * outW + csx], r, g, b, a, y, sx + 2, sy + 2);
					}
				}
				blend(r, g, b, a, y, PASS_ONE_WEIGHTS, -WGT1, WGT1 + 0.5f, result);
				out[py * outW + px] = pack(result);
			}
		}
		return out;
	}

	// A pixel of the half-filled image for the second pass: the taps lie on the pixels the first
	// pass wrote, where x plus y is even, and a tap clamped at the border is moved onto one.
	private static int filled(int[] out, int outW, int outH, int x, int y)
	{
		int cx = Math.min(Math.max(x, 0), outW - 1);
		int cy = Math.min(Math.max(y, 0), outH - 1);
		if (((cx + cy) & 1) != 0)
		{
			cx = cx + 1 < outW ? cx + 1 : cx - 1;
		}
		return out[cy * outW + cx];
	}

	private static void unpack(int argb, float[][] r, float[][] g, float[][] b, float[][] a, float[][] y, int i, int j)
	{
		float rr = (argb >> 16 & 0xff) / 255f;
		float gg = (argb >> 8 & 0xff) / 255f;
		float bb = (argb & 0xff) / 255f;
		float aa = (argb >>> 24) / 255f;
		r[i][j] = rr;
		g[i][j] = gg;
		b[i][j] = bb;
		a[i][j] = aa;
		// Edges are found in brightness, with the coverage counted in so cutout edges are edges too.
		y[i][j] = (0.2126f * rr + 0.7152f * gg + 0.0722f * bb) * 0.75f + aa * 0.25f;
	}

	// The new pixel: the average along the diagonal with less variation, sharpened with a small
	// negative lobe, and held within the range of the four pixels it sits between.
	private static void blend(float[][] r, float[][] g, float[][] b, float[][] a, float[][] y, float[] wp, float wNear, float wFar, float[] out)
	{
		boolean first = diagonalEdge(y, wp) <= 0f;
		out[0] = channel(r, first, wNear, wFar);
		out[1] = channel(g, first, wNear, wFar);
		out[2] = channel(b, first, wNear, wFar);
		out[3] = channel(a, first, wNear, wFar);
	}

	private static float channel(float[][] c, boolean first, float wNear, float wFar)
	{
		float v = first
			? wNear * (c[0][3] + c[3][0]) + wFar * (c[1][2] + c[2][1])
			: wNear * (c[0][0] + c[3][3]) + wFar * (c[1][1] + c[2][2]);
		float lo = Math.min(Math.min(c[1][1], c[2][1]), Math.min(c[1][2], c[2][2]));
		float hi = Math.max(Math.max(c[1][1], c[2][1]), Math.max(c[1][2], c[2][2]));
		return Math.min(Math.max(v, lo), hi);
	}

	private static float diagonalEdge(float[][] m, float[] wp)
	{
		float dw1 = wp[0] * (df(m[0][2], m[1][1]) + df(m[1][1], m[2][0]) + df(m[1][3], m[2][2]) + df(m[2][2], m[3][1]))
			+ wp[1] * (df(m[0][3], m[1][2]) + df(m[2][1], m[3][0]))
			+ wp[2] * (df(m[0][3], m[2][1]) + df(m[1][2], m[3][0]))
			+ wp[3] * df(m[1][2], m[2][1])
			+ wp[4] * (df(m[0][2], m[2][0]) + df(m[1][3], m[3][1]))
			+ wp[5] * (df(m[0][1], m[1][0]) + df(m[2][3], m[3][2]));
		float dw2 = wp[0] * (df(m[0][1], m[1][2]) + df(m[1][2], m[2][3]) + df(m[1][0], m[2][1]) + df(m[2][1], m[3][2]))
			+ wp[1] * (df(m[0][0], m[1][1]) + df(m[2][2], m[3][3]))
			+ wp[2] * (df(m[0][0], m[2][2]) + df(m[1][1], m[3][3]))
			+ wp[3] * df(m[1][1], m[2][2])
			+ wp[4] * (df(m[1][0], m[3][2]) + df(m[0][1], m[2][3]))
			+ wp[5] * (df(m[0][2], m[1][3]) + df(m[2][0], m[3][1]));
		return dw1 - dw2;
	}

	private static float df(float a, float b)
	{
		return Math.abs(a - b);
	}

	private static int pack(float[] c)
	{
		return Math.round(Math.min(Math.max(c[3], 0f), 1f) * 255f) << 24
			| Math.round(Math.min(Math.max(c[0], 0f), 1f) * 255f) << 16
			| Math.round(Math.min(Math.max(c[1], 0f), 1f) * 255f) << 8
			| Math.round(Math.min(Math.max(c[2], 0f), 1f) * 255f);
	}

	// Transparent texels take the mean colour of their opaque neighbours, spreading outward a few
	// texels, so the scaler never blends the black behind a cutout into its edge.
	private static int[] dilated(int[] argb, int size)
	{
		int[] current = argb.clone();
		for (int round = 0; round < 3; ++round)
		{
			int[] next = current.clone();
			boolean changed = false;
			for (int y = 0; y < size; ++y)
			{
				for (int x = 0; x < size; ++x)
				{
					int p = current[y * size + x];
					if ((p >>> 24) != 0)
					{
						continue;
					}
					int r = 0, g = 0, b = 0, n = 0;
					for (int dy = -1; dy <= 1; ++dy)
					{
						for (int dx = -1; dx <= 1; ++dx)
						{
							int nx = x + dx, ny = y + dy;
							if (nx < 0 || ny < 0 || nx >= size || ny >= size)
							{
								continue;
							}
							int q = current[ny * size + nx];
							if ((q >>> 24) != 0)
							{
								r += q >> 16 & 0xff;
								g += q >> 8 & 0xff;
								b += q & 0xff;
								++n;
							}
						}
					}
					if (n > 0)
					{
						next[y * size + x] = (r / n) << 16 | (g / n) << 8 | (b / n);
						changed = true;
					}
				}
			}
			current = next;
			if (!changed)
			{
				break;
			}
		}
		return current;
	}

	/** Halves a square ARGB image by averaging each two-by-two, alpha included. */
	public static int[] halved(int[] argb, int size)
	{
		int half = size / 2;
		int[] out = new int[half * half];
		for (int y = 0; y < half; ++y)
		{
			for (int x = 0; x < half; ++x)
			{
				int a = 0, r = 0, g = 0, b = 0;
				for (int dy = 0; dy < 2; ++dy)
				{
					for (int dx = 0; dx < 2; ++dx)
					{
						int p = argb[(y * 2 + dy) * size + x * 2 + dx];
						a += p >>> 24;
						r += p >> 16 & 0xff;
						g += p >> 8 & 0xff;
						b += p & 0xff;
					}
				}
				out[y * half + x] = (a / 4) << 24 | (r / 4) << 16 | (g / 4) << 8 | (b / 4);
			}
		}
		return out;
	}

	/** Reads a player's own texture and resamples it to the wanted size; null when there is none. */
	public static int[] override(File file, int size)
	{
		if (!file.isFile())
		{
			return null;
		}
		BufferedImage image;
		try
		{
			image = ImageIO.read(file);
		}
		catch (IOException e)
		{
			log.warn("Texture override {} could not be read", file, e);
			return null;
		}
		if (image == null)
		{
			return null;
		}
		BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = scaled.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		graphics.drawImage(image, 0, 0, size, size, null);
		graphics.dispose();
		return scaled.getRGB(0, 0, size, size, null, 0, size);
	}

	/** Writes a texture as a PNG, alpha included, unless the file already exists. */
	public static void export(File file, int[] argb, int size)
	{
		if (file.exists())
		{
			return;
		}
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, size, size, argb, 0, size);
		try
		{
			file.getParentFile().mkdirs();
			ImageIO.write(image, "png", file);
		}
		catch (IOException e)
		{
			log.warn("Texture {} not exported", file, e);
		}
	}

	/**
	 * A game texture at the target size: the player's override when there is one, else the
	 * original doubled by Super-xBR as often as it takes, else the original as it is.
	 */
	public static int[] upscaled(int[] argb, int size, int target, File override)
	{
		int[] own = override == null ? null : override(override, target);
		if (own != null)
		{
			return own;
		}
		int[] current = argb;
		int currentSize = size;
		while (currentSize < target)
		{
			current = superXbr(current, currentSize);
			currentSize *= 2;
		}
		return current;
	}

	/** Which of the textures have transparent texels, by the client's convention of a zero pixel. */
	public static BitSet cutouts(int[][] textures)
	{
		BitSet cutouts = new BitSet();
		for (int id = 0; id < textures.length; ++id)
		{
			int[] pixels = textures[id];
			if (pixels == null)
			{
				continue;
			}
			for (int p : pixels)
			{
				if (p == 0)
				{
					cutouts.set(id);
					break;
				}
			}
		}
		return cutouts;
	}
}
