package rltx.scene;

import java.util.Arrays;

/**
 * Non-indexed triangle soup: nine floats of position, one RGBA8 colour, a texture id and
 * three UV pairs per face. Texture id 0 means untextured; otherwise it is the client id plus one.
 */
public final class GeometryBuffer
{
	public static final int FLOATS_PER_FACE = 9;
	public static final int UV_FLOATS_PER_FACE = 6;

	private float[] pos;
	/** Where each face was on the previous frame; equal to {@link #pos} for faces without history. */
	private float[] prev;
	private int[] col;
	private int[] tex;
	private float[] uv;
	private int faces;

	public GeometryBuffer(int initialFaces)
	{
		pos = new float[initialFaces * FLOATS_PER_FACE];
		prev = new float[initialFaces * FLOATS_PER_FACE];
		col = new int[initialFaces];
		tex = new int[initialFaces];
		uv = new float[initialFaces * UV_FLOATS_PER_FACE];
	}

	public int faces()
	{
		return faces;
	}

	public float[] positions()
	{
		return pos;
	}

	public float[] previousPositions()
	{
		return prev;
	}

	/** Copies previous-frame positions for {@code count} faces starting at {@code firstFace}. */
	public void setPreviousPositions(int firstFace, float[] source, int count)
	{
		System.arraycopy(source, 0, prev, firstFace * FLOATS_PER_FACE, count * FLOATS_PER_FACE);
	}

	public int[] colors()
	{
		return col;
	}

	public int[] textures()
	{
		return tex;
	}

	public float[] uvs()
	{
		return uv;
	}

	public void clear()
	{
		faces = 0;
	}

	public void ensure(int extraFaces)
	{
		int need = faces + extraFaces;
		if (need > col.length)
		{
			int cap = Math.max(need, col.length * 2);
			pos = Arrays.copyOf(pos, cap * FLOATS_PER_FACE);
			prev = Arrays.copyOf(prev, cap * FLOATS_PER_FACE);
			col = Arrays.copyOf(col, cap);
			tex = Arrays.copyOf(tex, cap);
			uv = Arrays.copyOf(uv, cap * UV_FLOATS_PER_FACE);
		}
	}

	public void face(
		float x0, float y0, float z0,
		float x1, float y1, float z1,
		float x2, float y2, float z2,
		int rgba)
	{
		face(x0, y0, z0, x1, y1, z1, x2, y2, z2, rgba, 0, 0f, 0f, 0f, 0f, 0f, 0f);
	}

	public void face(
		float x0, float y0, float z0,
		float x1, float y1, float z1,
		float x2, float y2, float z2,
		int rgba, int texture,
		float u0, float v0, float u1, float v1, float u2, float v2)
	{
		ensure(1);
		int t = faces * UV_FLOATS_PER_FACE;
		uv[t] = u0;
		uv[t + 1] = v0;
		uv[t + 2] = u1;
		uv[t + 3] = v1;
		uv[t + 4] = u2;
		uv[t + 5] = v2;
		tex[faces] = texture;
		int o = faces * FLOATS_PER_FACE;
		pos[o] = x0;
		pos[o + 1] = y0;
		pos[o + 2] = z0;
		pos[o + 3] = x1;
		pos[o + 4] = y1;
		pos[o + 5] = z1;
		pos[o + 6] = x2;
		pos[o + 7] = y2;
		pos[o + 8] = z2;
		System.arraycopy(pos, o, prev, o, FLOATS_PER_FACE);
		col[faces] = rgba;
		++faces;
	}

	public void append(GeometryBuffer other)
	{
		ensure(other.faces);
		System.arraycopy(other.pos, 0, pos, faces * FLOATS_PER_FACE, other.faces * FLOATS_PER_FACE);
		System.arraycopy(other.prev, 0, prev, faces * FLOATS_PER_FACE, other.faces * FLOATS_PER_FACE);
		System.arraycopy(other.col, 0, col, faces, other.faces);
		System.arraycopy(other.tex, 0, tex, faces, other.faces);
		System.arraycopy(other.uv, 0, uv, faces * UV_FLOATS_PER_FACE, other.faces * UV_FLOATS_PER_FACE);
		faces += other.faces;
	}
}
