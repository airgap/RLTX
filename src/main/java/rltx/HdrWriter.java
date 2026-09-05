package rltx;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes linear RGB floats as a Radiance .hdr file: a shared exponent per pixel, uncompressed. */
final class HdrWriter
{
	private HdrWriter()
	{
	}

	static void write(File file, int width, int height, float[] rgb) throws IOException
	{
		try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16))
		{
			out.write(("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y " + height + " +X " + width + "\n").getBytes(StandardCharsets.US_ASCII));
			byte[] pixel = new byte[4];
			for (int i = 0; i < width * height; ++i)
			{
				float r = Math.max(rgb[i * 3], 0f);
				float g = Math.max(rgb[i * 3 + 1], 0f);
				float b = Math.max(rgb[i * 3 + 2], 0f);
				float largest = Math.max(r, Math.max(g, b));
				if (largest < 1e-32f || Float.isNaN(largest))
				{
					pixel[0] = pixel[1] = pixel[2] = pixel[3] = 0;
				}
				else
				{
					int exponent = Math.getExponent(largest) + 1;
					double scale = Math.scalb(256.0, -exponent);
					pixel[0] = (byte) Math.min(255, (int) (r * scale));
					pixel[1] = (byte) Math.min(255, (int) (g * scale));
					pixel[2] = (byte) Math.min(255, (int) (b * scale));
					pixel[3] = (byte) (exponent + 128);
				}
				out.write(pixel);
			}
		}
	}
}
