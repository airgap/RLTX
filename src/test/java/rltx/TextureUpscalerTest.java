package rltx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.Test;
import rltx.scene.TextureUpscaler;

public class TextureUpscalerTest
{
	private static int[] pattern(int size)
	{
		int[] argb = new int[size * size];
		for (int y = 0; y < size; ++y)
		{
			for (int x = 0; x < size; ++x)
			{
				boolean stripe = (x + y) / 6 % 2 == 0;
				boolean disc = (x - size / 2) * (x - size / 2) + (y - size / 2) * (y - size / 2) < size * size / 9;
				int colour = disc ? 0xff2060c0 : stripe ? 0xffe0d0a0 : 0xff604020;
				// A transparent corner, as the client marks cutouts: a zero pixel.
				if (x + y < size / 3)
				{
					colour = 0;
				}
				argb[y * size + x] = colour;
			}
		}
		return argb;
	}

	@Test
	public void doublesAndKeepsSolidColour()
	{
		int[] solid = new int[16 * 16];
		java.util.Arrays.fill(solid, 0xff336699);
		int[] out = TextureUpscaler.superXbr(solid, 16);
		assertEquals(32 * 32, out.length);
		for (int p : out)
		{
			assertEquals(0xff336699, p);
		}
	}

	@Test
	public void keepsHardEdgesWithinTheirColours()
	{
		int[] in = new int[16 * 16];
		for (int i = 0; i < in.length; ++i)
		{
			in[i] = (i % 16) < 8 ? 0xff000000 : 0xffffffff;
		}
		int[] out = TextureUpscaler.superXbr(in, 16);
		for (int p : out)
		{
			int r = p >> 16 & 0xff;
			int g = p >> 8 & 0xff;
			int b = p & 0xff;
			assertTrue(r == g && g == b);
		}
		// Far from the edge the colours are untouched.
		assertEquals(0xff000000, out[5 * 32 + 3]);
		assertEquals(0xffffffff, out[5 * 32 + 28]);
	}

	@Test
	public void transparentTexelsStayTransparentAndOpaqueOpaque()
	{
		int[] in = pattern(32);
		int[] out = TextureUpscaler.upscaled(in, 32, 128, null);
		assertEquals(128 * 128, out.length);
		assertEquals(0, out[0] >>> 24);
		assertEquals(0xff, out[127 * 128 + 127] >>> 24);
		int[] mip = TextureUpscaler.halved(out, 128);
		assertEquals(64 * 64, mip.length);
	}

	@Test
	public void writesAPictureToLookAt() throws IOException
	{
		int size = 32;
		int[] in = pattern(size);
		int[] out = TextureUpscaler.upscaled(in, size, size * 4, null);
		File dir = new File("build/test-output");
		dir.mkdirs();
		BufferedImage before = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		before.setRGB(0, 0, size, size, in, 0, size);
		ImageIO.write(before, "png", new File(dir, "superxbr-before.png"));
		BufferedImage after = new BufferedImage(size * 4, size * 4, BufferedImage.TYPE_INT_ARGB);
		after.setRGB(0, 0, size * 4, size * 4, out, 0, size * 4);
		ImageIO.write(after, "png", new File(dir, "superxbr-after.png"));
	}
}
