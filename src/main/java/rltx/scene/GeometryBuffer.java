package rltx.scene;

import java.util.Arrays;

/**
 * Non-indexed triangle soup: nine floats of position, one RGBA8 colour, a texture id, three UV
 * pairs and three packed vertex normals per face. Texture id 0 means untextured; otherwise it is
 * the client id plus one. A normal of zero leaves the face flat-shaded by its geometric normal.
 */
public final class GeometryBuffer
{
	public static final int FLOATS_PER_FACE = 9;
	public static final int UV_FLOATS_PER_FACE = 6;
	public static final int NORMALS_PER_FACE = 3;

	private float[] pos;
	/** Where each face was on the previous frame; equal to {@link #pos} for faces without history. */
	private float[] prev;
	private int[] col;
	private int[] tex;
	private float[] uv;
	/** Per vertex, x, y and z as signed bytes in the low three bytes; zero for a flat face. */
	private int[] nrm;
	private int faces;

	public GeometryBuffer(int initialFaces)
	{
		pos = new float[initialFaces * FLOATS_PER_FACE];
		prev = new float[initialFaces * FLOATS_PER_FACE];
		col = new int[initialFaces];
		tex = new int[initialFaces];
		uv = new float[initialFaces * UV_FLOATS_PER_FACE];
		nrm = new int[initialFaces * NORMALS_PER_FACE];
	}

	/** Packs a direction as three signed bytes; zero is never produced for a non-zero direction. */
	public static int packNormal(float x, float y, float z)
	{
		float len = (float) Math.sqrt(x * x + y * y + z * z);
		if (len == 0f)
		{
			return 0;
		}
		float k = 127f / len;
		int px = Math.round(x * k) & 0xff;
		int py = Math.round(y * k) & 0xff;
		int pz = Math.round(z * k) & 0xff;
		return px | py << 8 | pz << 16 | 1 << 24;
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

	public int[] normals()
	{
		return nrm;
	}

	/** Gives the face pushed last its three vertex normals, as {@link #packNormal} packs them. */
	public void lastNormals(int n0, int n1, int n2)
	{
		int o = (faces - 1) * NORMALS_PER_FACE;
		nrm[o] = n0;
		nrm[o + 1] = n1;
		nrm[o + 2] = n2;
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
			nrm = Arrays.copyOf(nrm, cap * NORMALS_PER_FACE);
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
		int n = faces * NORMALS_PER_FACE;
		nrm[n] = 0;
		nrm[n + 1] = 0;
		nrm[n + 2] = 0;
		++faces;
	}

	/** Appends another buffer's faces moved by an offset. */
	public void append(GeometryBuffer other, float dx, float dy, float dz)
	{
		int first = faces;
		append(other);
		for (int i = first * FLOATS_PER_FACE; i < faces * FLOATS_PER_FACE; i += 3)
		{
			pos[i] += dx;
			pos[i + 1] += dy;
			pos[i + 2] += dz;
			prev[i] += dx;
			prev[i + 1] += dy;
			prev[i + 2] += dz;
		}
	}

	public void append(GeometryBuffer other)
	{
		ensure(other.faces);
		System.arraycopy(other.pos, 0, pos, faces * FLOATS_PER_FACE, other.faces * FLOATS_PER_FACE);
		System.arraycopy(other.prev, 0, prev, faces * FLOATS_PER_FACE, other.faces * FLOATS_PER_FACE);
		System.arraycopy(other.col, 0, col, faces, other.faces);
		System.arraycopy(other.tex, 0, tex, faces, other.faces);
		System.arraycopy(other.uv, 0, uv, faces * UV_FLOATS_PER_FACE, other.faces * UV_FLOATS_PER_FACE);
		System.arraycopy(other.nrm, 0, nrm, faces * NORMALS_PER_FACE, other.faces * NORMALS_PER_FACE);
		faces += other.faces;
	}
}
