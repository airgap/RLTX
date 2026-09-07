package rltx.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Test;

public class WebPTest
{
	private static int[] frame(int size, int phase)
	{
		int[] argb = new int[size * size];
		for (int y = 0; y < size; ++y)
		{
			for (int x = 0; x < size; ++x)
			{
				int r = (x * 255 / (size - 1) + phase) & 0xff;
				int g = y * 255 / (size - 1);
				int b = ((x + y) / 8 % 2 == 0) ? 40 : 200;
				boolean disc = (x - size / 2) * (x - size / 2) + (y - size / 2) * (y - size / 2) < size * size / 12;
				argb[y * size + x] = disc ? 0xff2060c0 : 0xff000000 | r << 16 | g << 8 | b;
			}
		}
		return argb;
	}

	@Test
	public void writesFilesToDecode() throws IOException
	{
		File dir = new File("build/test-output");
		dir.mkdirs();
		int size = 96;
		byte[] still = WebP.still(frame(size, 0), size, size);
		assertEquals("RIFF", new String(still, 0, 4, "US-ASCII"));
		assertEquals("WEBP", new String(still, 8, 4, "US-ASCII"));
		Files.write(new File(dir, "still.webp").toPath(), still);
		byte[] anim = WebP.animation(size, size, Arrays.asList(frame(size, 0), frame(size, 64), frame(size, 128)), new int[]{100, 100, 100});
		Files.write(new File(dir, "anim.webp").toPath(), anim);
		// A gradient with a repeating pattern compresses well below its raw size.
		assertTrue(still.length < size * size * 4 / 2);
	}

	@Test
	public void prefixCodesRoundTrip()
	{
		for (int v = 1; v <= 4096; ++v)
		{
			int prefix = v < 5 ? v - 1 : 2 * (31 - Integer.numberOfLeadingZeros(v - 1)) + ((v - 1) >>> (31 - Integer.numberOfLeadingZeros(v - 1) - 1) & 1);
			int extraBits = prefix < 4 ? 0 : (prefix - 2) >> 1;
			int offset = prefix < 4 ? prefix : (2 + (prefix & 1)) << extraBits;
			int extra = prefix < 4 ? 0 : (v - 1) & (1 << extraBits) - 1;
			assertEquals(v, offset + extra + 1);
		}
	}
}
