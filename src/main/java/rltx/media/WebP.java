package rltx.media;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A lossless WebP writer: VP8L frames, each predicted from its left neighbour with green taken
 * out of red and blue, then coded as LZ77 references and literals under canonical prefix codes,
 * wrapped one after another in an animation. Enough of the format for a rendered figure to be
 * put up as a moving picture without any native library.
 */
public final class WebP
{
	private static final int MAX_MATCH = 4096;
	private static final int MIN_MATCH = 3;
	private static final int CHAIN = 32;
	private static final int GREEN_ALPHABET = 256 + 24;
	private static final int DISTANCE_ALPHABET = 40;
	/** The order the code-length code's lengths are written in. */
	private static final int[] CODE_LENGTH_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

	private WebP()
	{
	}

	/**
	 * An animation of frames all of one size, each shown for its duration in milliseconds, looping
	 * forever. Frames are ARGB; the alpha channel is kept when any pixel is not opaque.
	 */
	public static byte[] animation(int width, int height, List<int[]> frames, int[] durations)
	{
		boolean alpha = false;
		for (int[] frame : frames)
		{
			for (int p : frame)
			{
				if ((p >>> 24) != 0xff)
				{
					alpha = true;
					break;
				}
			}
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		// VP8X: animation, and alpha where used; canvas size less one in 24 bits each.
		byte[] vp8x = new byte[10];
		vp8x[0] = (byte) ((alpha ? 0x10 : 0) | 0x02);
		put24(vp8x, 4, width - 1);
		put24(vp8x, 7, height - 1);
		chunk(body, "VP8X", vp8x);
		// ANIM: transparent black behind, looping without end.
		chunk(body, "ANIM", new byte[6]);
		for (int i = 0; i < frames.size(); ++i)
		{
			byte[] image = vp8l(frames.get(i), width, height, alpha);
			ByteArrayOutputStream frame = new ByteArrayOutputStream();
			byte[] head = new byte[16];
			put24(head, 6, width - 1);
			put24(head, 9, height - 1);
			put24(head, 12, Math.max(durations[i], 1));
			// Not blended with what came before, and left in place after.
			head[15] = 0x02;
			frame.write(head, 0, head.length);
			chunk(frame, "VP8L", image);
			chunk(body, "ANMF", frame.toByteArray());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] content = body.toByteArray();
		out.write('R');
		out.write('I');
		out.write('F');
		out.write('F');
		put32(out, content.length + 4);
		out.write('W');
		out.write('E');
		out.write('B');
		out.write('P');
		out.write(content, 0, content.length);
		return out.toByteArray();
	}

	/** One still image, for a file of its own. */
	public static byte[] still(int[] argb, int width, int height)
	{
		boolean alpha = false;
		for (int p : argb)
		{
			if ((p >>> 24) != 0xff)
			{
				alpha = true;
				break;
			}
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		chunk(body, "VP8L", vp8l(argb, width, height, alpha));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] content = body.toByteArray();
		out.write('R');
		out.write('I');
		out.write('F');
		out.write('F');
		put32(out, content.length + 4);
		out.write('W');
		out.write('E');
		out.write('B');
		out.write('P');
		out.write(content, 0, content.length);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String fourcc, byte[] payload)
	{
		for (int i = 0; i < 4; ++i)
		{
			out.write(fourcc.charAt(i));
		}
		put32(out, payload.length);
		out.write(payload, 0, payload.length);
		if ((payload.length & 1) != 0)
		{
			out.write(0);
		}
	}

	private static void put32(ByteArrayOutputStream out, int v)
	{
		out.write(v & 0xff);
		out.write(v >>> 8 & 0xff);
		out.write(v >>> 16 & 0xff);
		out.write(v >>> 24 & 0xff);
	}

	private static void put24(byte[] b, int at, int v)
	{
		b[at] = (byte) v;
		b[at + 1] = (byte) (v >>> 8);
		b[at + 2] = (byte) (v >>> 16);
	}

	// ---- VP8L ---------------------------------------------------------------------------------

	/** The VP8L payload of one image: header, transforms, prefix codes and the coded pixels. */
	static byte[] vp8l(int[] argb, int width, int height, boolean alpha)
	{
		BitWriter bits = new BitWriter();
		bits.write(0x2f, 8);
		bits.write(width - 1, 14);
		bits.write(height - 1, 14);
		bits.write(alpha ? 1 : 0, 1);
		bits.write(0, 3);

		// Predictor transform: one block over the whole image, every pixel guessed from its left
		// neighbour, so runs of flat shading and smooth gradients turn into runs of near nothing.
		bits.write(1, 1);
		bits.write(0, 2);
		int sizeBits = 9;
		bits.write(sizeBits - 2, 3);
		int blocksX = (width + (1 << sizeBits) - 1) >> sizeBits;
		int blocksY = (height + (1 << sizeBits) - 1) >> sizeBits;
		int[] modes = new int[blocksX * blocksY];
		java.util.Arrays.fill(modes, 0xff000000 | 1 << 8);
		subImage(bits, modes, blocksX, blocksY);
		int[] residual = new int[argb.length];
		for (int y = 0; y < height; ++y)
		{
			for (int x = 0; x < width; ++x)
			{
				int i = y * width + x;
				int predicted = x == 0 && y == 0 ? 0xff000000 : y == 0 ? argb[i - 1] : x == 0 ? argb[i - width] : argb[i - 1];
				residual[i] = subtract(argb[i], predicted);
			}
		}
		// Subtract green: red and blue usually follow green, so what remains of them is small.
		bits.write(1, 1);
		bits.write(2, 2);
		for (int i = 0; i < residual.length; ++i)
		{
			int p = residual[i];
			int g = p >>> 8 & 0xff;
			int r = (p >>> 16 & 0xff) - g & 0xff;
			int b = (p & 0xff) - g & 0xff;
			residual[i] = p & 0xff00ff00 | r << 16 | b;
		}
		bits.write(0, 1);

		// No colour cache, one group of prefix codes.
		bits.write(0, 1);
		bits.write(0, 1);
		image(bits, residual, width, height);
		return bits.finish();
	}

	private static int subtract(int a, int b)
	{
		int alpha = (a >>> 24) - (b >>> 24) & 0xff;
		int red = (a >>> 16 & 0xff) - (b >>> 16 & 0xff) & 0xff;
		int green = (a >>> 8 & 0xff) - (b >>> 8 & 0xff) & 0xff;
		int blue = (a & 0xff) - (b & 0xff) & 0xff;
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	// A transform's sub-image: no colour cache, then its prefix codes and pixels.
	private static void subImage(BitWriter bits, int[] pixels, int width, int height)
	{
		bits.write(0, 1);
		image(bits, pixels, width, height);
	}

	// Tokenises the pixels, writes the five prefix codes for what they use, then the tokens.
	private static void image(BitWriter bits, int[] pixels, int width, int height)
	{
		List<int[]> tokens = tokenize(pixels);
		int[] green = new int[GREEN_ALPHABET];
		int[] red = new int[256];
		int[] blue = new int[256];
		int[] alpha = new int[256];
		int[] distance = new int[DISTANCE_ALPHABET];
		for (int[] token : tokens)
		{
			if (token[0] == 0)
			{
				int p = token[1];
				++green[p >>> 8 & 0xff];
				++red[p >>> 16 & 0xff];
				++blue[p & 0xff];
				++alpha[p >>> 24];
			}
			else
			{
				++green[256 + prefix(token[1])];
				++distance[prefix(token[2] + 120)];
			}
		}
		Code greenCode = Code.build(green, 15);
		Code redCode = Code.build(red, 15);
		Code blueCode = Code.build(blue, 15);
		Code alphaCode = Code.build(alpha, 15);
		Code distanceCode = Code.build(distance, 15);
		greenCode.writeDefinition(bits);
		redCode.writeDefinition(bits);
		blueCode.writeDefinition(bits);
		alphaCode.writeDefinition(bits);
		distanceCode.writeDefinition(bits);
		for (int[] token : tokens)
		{
			if (token[0] == 0)
			{
				int p = token[1];
				greenCode.writeSymbol(bits, p >>> 8 & 0xff);
				redCode.writeSymbol(bits, p >>> 16 & 0xff);
				blueCode.writeSymbol(bits, p & 0xff);
				alphaCode.writeSymbol(bits, p >>> 24);
			}
			else
			{
				greenCode.writeSymbol(bits, 256 + prefix(token[1]));
				writeExtra(bits, token[1]);
				int code = token[2] + 120;
				distanceCode.writeSymbol(bits, prefix(code));
				writeExtra(bits, code);
			}
		}
	}

	// A value of one or more as its prefix symbol: the four smallest stand alone, the rest share
	// a symbol by their two leading bits and carry the remainder as extra bits.
	private static int prefix(int value)
	{
		int v = value - 1;
		if (v < 4)
		{
			return v;
		}
		int high = 31 - Integer.numberOfLeadingZeros(v);
		int second = v >>> (high - 1) & 1;
		return 2 * high + second;
	}

	private static void writeExtra(BitWriter bits, int value)
	{
		int v = value - 1;
		if (v < 4)
		{
			return;
		}
		int high = 31 - Integer.numberOfLeadingZeros(v);
		int extraBits = high - 1;
		bits.write(v & (1 << extraBits) - 1, extraBits);
	}

	// Greedy LZ77 over pixels: a token is {0, pixel} for a literal or {1, length, distance} for a
	// copy of earlier pixels, found through a hash of three pixels and a short chain of candidates.
	private static List<int[]> tokenize(int[] pixels)
	{
		List<int[]> tokens = new ArrayList<>();
		int n = pixels.length;
		int hashBits = 16;
		int[] head = new int[1 << hashBits];
		java.util.Arrays.fill(head, -1);
		int[] previous = new int[n];
		int i = 0;
		while (i < n)
		{
			int bestLength = 0;
			int bestDistance = 0;
			if (i + MIN_MATCH <= n)
			{
				int h = hash(pixels, i, hashBits);
				int candidate = head[h];
				int limit = Math.min(MAX_MATCH, n - i);
				for (int c = 0; c < CHAIN && candidate >= 0; ++c)
				{
					int length = 0;
					while (length < limit && pixels[candidate + length] == pixels[i + length])
					{
						++length;
					}
					if (length > bestLength)
					{
						bestLength = length;
						bestDistance = i - candidate;
						if (length == limit)
						{
							break;
						}
					}
					candidate = previous[candidate];
				}
				previous[i] = head[h];
				head[h] = i;
			}
			if (bestLength >= MIN_MATCH)
			{
				tokens.add(new int[]{1, bestLength, bestDistance});
				for (int k = 1; k < bestLength && i + k + MIN_MATCH <= n; ++k)
				{
					int h = hash(pixels, i + k, hashBits);
					previous[i + k] = head[h];
					head[h] = i + k;
				}
				i += bestLength;
			}
			else
			{
				tokens.add(new int[]{0, pixels[i]});
				++i;
			}
		}
		return tokens;
	}

	private static int hash(int[] pixels, int at, int bits)
	{
		int h = pixels[at] * 0x9E3779B1 ^ pixels[at + 1] * 0x85EBCA77 ^ pixels[at + 2] * 0xC2B2AE3D;
		return h >>> (32 - bits);
	}

	/** A canonical prefix code over an alphabet, and how it is written to the stream. */
	static final class Code
	{
		final int[] lengths;
		final int[] codes;
		final int used;
		final int single;

		private Code(int[] lengths, int used, int single)
		{
			this.lengths = lengths;
			this.used = used;
			this.single = single;
			codes = new int[lengths.length];
			// Canonical assignment, shorter codes first, then each code stored reversed so it can
			// be written least significant bit first as the decoder reads it.
			int maxLength = 0;
			for (int l : lengths)
			{
				maxLength = Math.max(maxLength, l);
			}
			int[] count = new int[maxLength + 2];
			for (int l : lengths)
			{
				if (l > 0)
				{
					++count[l];
				}
			}
			int[] next = new int[maxLength + 2];
			int code = 0;
			for (int l = 1; l <= maxLength; ++l)
			{
				code = (code + count[l - 1]) << 1;
				next[l] = code;
			}
			for (int s = 0; s < lengths.length; ++s)
			{
				if (lengths[s] > 0)
				{
					codes[s] = Integer.reverse(next[lengths[s]]++) >>> (32 - lengths[s]);
				}
			}
		}

		/** Lengths from frequencies, none longer than the limit; a lone symbol gets a zero-length code. */
		static Code build(int[] frequencies, int limit)
		{
			int n = frequencies.length;
			int used = 0;
			int single = 0;
			for (int s = 0; s < n; ++s)
			{
				if (frequencies[s] > 0)
				{
					++used;
					single = s;
				}
			}
			int[] lengths = new int[n];
			if (used <= 1)
			{
				return new Code(lengths, used, single);
			}
			// Huffman by repeated merging of the two lightest, depths read back off the tree.
			int[] weight = new int[2 * n];
			int[] parent = new int[2 * n];
			int[] depth = new int[2 * n];
			java.util.PriorityQueue<Integer> heap = new java.util.PriorityQueue<>((a, b) -> weight[a] != weight[b] ? Integer.compare(weight[a], weight[b]) : Integer.compare(a, b));
			for (int s = 0; s < n; ++s)
			{
				if (frequencies[s] > 0)
				{
					weight[s] = frequencies[s];
					heap.add(s);
				}
			}
			int nodes = n;
			while (heap.size() > 1)
			{
				int a = heap.poll();
				int b = heap.poll();
				weight[nodes] = weight[a] + weight[b];
				parent[a] = nodes;
				parent[b] = nodes;
				heap.add(nodes);
				++nodes;
			}
			int root = nodes - 1;
			for (int k = root - 1; k >= 0; --k)
			{
				if (k < n && frequencies[k] == 0)
				{
					continue;
				}
				depth[k] = depth[parent[k]] + 1;
			}
			for (int s = 0; s < n; ++s)
			{
				lengths[s] = frequencies[s] > 0 ? depth[s] : 0;
			}
			limitLengths(lengths, frequencies, limit);
			return new Code(lengths, used, single);
		}

		// Codes deeper than the limit are pulled up to it and the Kraft sum restored by
		// lengthening the shallowest of the rest, the way deflate does.
		private static void limitLengths(int[] lengths, int[] frequencies, int limit)
		{
			boolean over = false;
			for (int l : lengths)
			{
				over |= l > limit;
			}
			if (!over)
			{
				return;
			}
			long kraft = 0;
			for (int s = 0; s < lengths.length; ++s)
			{
				if (lengths[s] > 0)
				{
					lengths[s] = Math.min(lengths[s], limit);
					kraft += 1L << (limit - lengths[s]);
				}
			}
			long full = 1L << limit;
			while (kraft > full)
			{
				// Deepen the shallowest code that is not yet at the limit, preferring the rarest.
				int pick = -1;
				for (int s = 0; s < lengths.length; ++s)
				{
					if (lengths[s] > 0 && lengths[s] < limit && (pick < 0 || lengths[s] > lengths[pick] || lengths[s] == lengths[pick] && frequencies[s] < frequencies[pick]))
					{
						pick = s;
					}
				}
				kraft -= 1L << (limit - lengths[pick] - 1);
				++lengths[pick];
			}
		}

		void writeSymbol(BitWriter bits, int symbol)
		{
			if (used <= 1)
			{
				return;
			}
			bits.write(codes[symbol], lengths[symbol]);
		}

		/** The code as the stream carries it: a simple code for one symbol, else the lengths under a code-length code. */
		void writeDefinition(BitWriter bits)
		{
			if (used <= 1)
			{
				bits.write(1, 1);
				bits.write(0, 1);
				if (single < 2)
				{
					bits.write(0, 1);
					bits.write(single, 1);
				}
				else
				{
					bits.write(1, 1);
					bits.write(single, 8);
				}
				return;
			}
			bits.write(0, 1);
			// The lengths as a run of code-length symbols: 0 to 15 literal, 16 repeats the last
			// length three to six times, 17 and 18 give runs of zeros.
			List<int[]> runs = new ArrayList<>();
			int i = 0;
			int previous = 8;
			while (i < lengths.length)
			{
				int l = lengths[i];
				int run = 1;
				while (i + run < lengths.length && lengths[i + run] == l)
				{
					++run;
				}
				if (l == 0)
				{
					int left = run;
					while (left >= 3)
					{
						if (left >= 11)
						{
							int take = Math.min(left, 138);
							runs.add(new int[]{18, take - 11});
							left -= take;
						}
						else
						{
							runs.add(new int[]{17, left - 3});
							left = 0;
						}
					}
					for (int k = 0; k < left; ++k)
					{
						runs.add(new int[]{0, 0});
					}
				}
				else
				{
					int left = run;
					if (l != previous)
					{
						runs.add(new int[]{l, 0});
						--left;
					}
					while (left >= 3)
					{
						int take = Math.min(left, 6);
						runs.add(new int[]{16, take - 3});
						left -= take;
					}
					for (int k = 0; k < left; ++k)
					{
						runs.add(new int[]{l, 0});
					}
					previous = l;
				}
				i += run;
			}
			int[] codeLengthFrequencies = new int[19];
			for (int[] r : runs)
			{
				++codeLengthFrequencies[r[0]];
			}
			Code codeLengthCode = Code.build(codeLengthFrequencies, 7);
			// A lone code-length symbol still needs a real one-bit code here, since this code is
			// always written in full.
			if (codeLengthCode.used == 1)
			{
				int[] two = codeLengthFrequencies.clone();
				two[codeLengthCode.single == 0 ? 1 : 0] = 1;
				codeLengthCode = Code.build(two, 7);
			}
			int count = 19;
			while (count > 4 && codeLengthCode.lengths[CODE_LENGTH_ORDER[count - 1]] == 0)
			{
				--count;
			}
			bits.write(count - 4, 4);
			for (int k = 0; k < count; ++k)
			{
				bits.write(codeLengthCode.lengths[CODE_LENGTH_ORDER[k]], 3);
			}
			bits.write(0, 1);
			for (int[] r : runs)
			{
				codeLengthCode.writeSymbol(bits, r[0]);
				if (r[0] == 16)
				{
					bits.write(r[1], 2);
				}
				else if (r[0] == 17)
				{
					bits.write(r[1], 3);
				}
				else if (r[0] == 18)
				{
					bits.write(r[1], 7);
				}
			}
		}
	}

	/** Bits packed least significant first into bytes. */
	static final class BitWriter
	{
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();
		private long buffer;
		private int count;

		void write(int value, int bits)
		{
			if (bits == 0)
			{
				return;
			}
			buffer |= ((long) value & (1L << bits) - 1) << count;
			count += bits;
			while (count >= 8)
			{
				out.write((int) (buffer & 0xff));
				buffer >>>= 8;
				count -= 8;
			}
		}

		byte[] finish()
		{
			if (count > 0)
			{
				out.write((int) (buffer & 0xff));
				buffer = 0;
				count = 0;
			}
			return out.toByteArray();
		}
	}
}
