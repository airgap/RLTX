package rltx.scene;

import net.runelite.api.Model;
import net.runelite.api.Perspective;

/**
 * Flattens a client model into world-space triangles with one flat color each. Opaque faces
 * and translucent faces go to separate buffers; a translucent face's opacity is stored in the
 * colour's alpha channel. Not thread safe; the scene loader and the frame path each own one.
 */
public final class ModelPusher
{
	private float[] tx = new float[4096];
	private float[] ty = new float[4096];
	private float[] tz = new float[4096];
	private final float[] u = new float[3];
	private final float[] v = new float[3];
	// Scene units per unit of the client's face depth bias. The vanilla renderer pulls biased
	// faces toward the camera in depth so hair beats hats and capes beat bodies; here they are
	// pushed out along the face normal instead.
	private static final float BIAS_OFFSET = 0.6f;
	/** Marks a face as flame: it glows with its own colour and flickers. */
	public static final int FLAME_BIT = 1 << 29;
	/** Whether hot-coloured faces of the models being pushed are flames of a light-bearing object. */
	public boolean flames;
	/** Horizontal centre and highest point, in world space, of the flame faces the last push marked. */
	public float flameX, flameTop, flameZ;
	public int flameFaces;
	/** Highlight palette index, 1 to 15, stamped on every face pushed; 0 for none. */
	public int highlight;

	// Vanilla flames are saturated orange to yellow faces; hue sits in the top six bits of the
	// packed HSL as sixty-fourths of a turn, saturation in three bits, lightness in seven.
	private static boolean hot(int hsl)
	{
		int hue = hsl >> 10 & 63;
		int saturation = hsl >> 7 & 7;
		int lightness = hsl & 127;
		return hue >= 1 && hue <= 9 && saturation >= 3 && lightness >= 40;
	}

	/**
	 * @param transform row-major 3x4 matrix placing a nested world view's local space in the
	 *                  world, or null for the top-level scene
	 */
	public void push(Model m, int orientation, int x, int y, int z, float[] transform, Palette palette, GeometryBuffer opaque, GeometryBuffer translucent)
	{
		final int vertexCount = m.getVerticesCount();
		if (vertexCount > tx.length)
		{
			int cap = Math.max(vertexCount, tx.length * 2);
			tx = new float[cap];
			ty = new float[cap];
			tz = new float[cap];
		}

		final float[] vx = m.getVerticesX();
		final float[] vy = m.getVerticesY();
		final float[] vz = m.getVerticesZ();

		float sin = 0, cos = 1;
		if (orientation != 0)
		{
			sin = Perspective.SINE[orientation] / 65536f;
			cos = Perspective.COSINE[orientation] / 65536f;
		}

		for (int v = 0; v < vertexCount; ++v)
		{
			float px = vx[v];
			float pz = vz[v];
			if (orientation != 0)
			{
				float x0 = px;
				px = pz * sin + x0 * cos;
				pz = pz * cos - x0 * sin;
			}
			px += x;
			float py = vy[v] + y;
			pz += z;
			if (transform != null)
			{
				tx[v] = transform[0] * px + transform[1] * py + transform[2] * pz + transform[3];
				ty[v] = transform[4] * px + transform[5] * py + transform[6] * pz + transform[7];
				tz[v] = transform[8] * px + transform[9] * py + transform[10] * pz + transform[11];
				continue;
			}
			tx[v] = px;
			ty[v] = py;
			tz[v] = pz;
		}

		flameFaces = 0;
		flameX = 0f;
		flameZ = 0f;
		flameTop = Float.MAX_VALUE;

		final int faceCount = m.getFaceCount();
		final int[] i1 = m.getFaceIndices1();
		final int[] i2 = m.getFaceIndices2();
		final int[] i3 = m.getFaceIndices3();
		final int[] c1 = m.getFaceColors1();
		final int[] c3 = m.getFaceColors3();
		final short[] unlit = m.getUnlitFaceColors();
		final short[] textures = m.getFaceTextures();
		final byte[] textureFaces = m.getTextureFaces();
		final int[] t1 = m.getTexIndices1();
		final int[] t2 = m.getTexIndices2();
		final int[] t3 = m.getTexIndices3();
		final byte[] transparencies = m.getFaceTransparencies();
		final byte[] biases = m.getFaceBias();
		final int modelTransparency = m.getTransparency() & 0xff;
		final int[] nX = m.getVertexNormalsX();
		final int[] nY = m.getVertexNormalsY();
		final int[] nZ = m.getVertexNormalsZ();
		final boolean undo = palette.undoShading && unlit == null && nX != null && nY != null && nZ != null;

		opaque.ensure(faceCount);
		for (int f = 0; f < faceCount; ++f)
		{
			if (c3[f] == -2)
			{
				continue;
			}
			// 253 and 254 mark click boxes and other faces the client never draws; a model
			// transparency of 255 hides the whole model.
			int t = faceTransparency(modelTransparency, transparencies != null ? transparencies[f] & 0xff : 0);
			if (t >= 253 || modelTransparency == 0xff)
			{
				continue;
			}
			boolean cutout = textures != null && textures[f] != -1 && TextureCutouts.isCutout(textures[f]);
			GeometryBuffer out = t == 0 && !cutout ? opaque : translucent;
			int opacity = (255 - t) << 24;

			int a = i1[f], b = i2[f], c = i3[f];
			// Some models carry a flat, translucent, untextured face at ground level standing in
			// for a shadow; under real shadows it is a dark smear, so it is dropped as 117 HD does.
			if (t > 100 && (textures == null || textures[f] == -1) && vy[a] == vy[b] && vy[a] == vy[c] && vy[a] >= -8f)
			{
				continue;
			}
			float ox = 0, oy = 0, oz = 0;
			int bias = biases != null ? biases[f] & 0xff : 0;
			if (bias != 0)
			{
				float ex = tx[b] - tx[a], ey = ty[b] - ty[a], ez = tz[b] - tz[a];
				float fx = tx[c] - tx[a], fy = ty[c] - ty[a], fz = tz[c] - tz[a];
				float nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len > 0)
				{
					float k = bias * BIAS_OFFSET / len;
					ox = nx * k;
					oy = ny * k;
					oz = nz * k;
				}
			}
			if (textures != null && textures[f] != -1)
			{
				faceUvs(vx, vy, vz, a, b, c, textureFaces, t1, t2, t3, f);
				out.face(tx[a] + ox, ty[a] + oy, tz[a] + oz, tx[b] + ox, ty[b] + oy, tz[b] + oz, tx[c] + ox, ty[c] + oy, tz[c] + oz,
					palette.texture(textures[f]) & 0xffffff | opacity, WaterTextures.encode(textures[f]) | highlight << 24, u[0], v[0], u[1], v[1], u[2], v[2]);
				continue;
			}
			int hsl = unlit != null ? unlit[f] & 0xffff : c1[f] & 0xffff;
			boolean flame = flames && hot(hsl);
			if (undo && !flame)
			{
				hsl = Palette.undoModelShading(hsl, nX[a] + nX[b] + nX[c], nY[a] + nY[b] + nY[c], nZ[a] + nZ[b] + nZ[c]);
			}
			int rgb = palette.hsl(hsl) & 0xffffff;
			if (flame)
			{
				out.face(tx[a] + ox, ty[a] + oy, tz[a] + oz, tx[b] + ox, ty[b] + oy, tz[b] + oz, tx[c] + ox, ty[c] + oy, tz[c] + oz,
					rgb | opacity, FLAME_BIT | highlight << 24, 0f, 0f, 0f, 0f, 0f, 0f);
				flameX += tx[a] + tx[b] + tx[c];
				flameZ += tz[a] + tz[b] + tz[c];
				flameTop = Math.min(flameTop, Math.min(ty[a], Math.min(ty[b], ty[c])));
				++flameFaces;
				continue;
			}
			if (highlight != 0)
			{
				out.face(tx[a] + ox, ty[a] + oy, tz[a] + oz, tx[b] + ox, ty[b] + oy, tz[b] + oz, tx[c] + ox, ty[c] + oy, tz[c] + oz,
					rgb | opacity, highlight << 24, 0f, 0f, 0f, 0f, 0f, 0f);
				continue;
			}
			out.face(tx[a] + ox, ty[a] + oy, tz[a] + oz, tx[b] + ox, ty[b] + oy, tz[b] + oz, tx[c] + ox, ty[c] + oy, tz[c] + oz, rgb | opacity);
		}
		if (flameFaces > 0)
		{
			flameX /= 3f * flameFaces;
			flameZ /= 3f * flameFaces;
		}
	}

	// Texture coordinates from the client's texture triangle: the face's vertices expressed in
	// the basis of the three texture-mapping vertices P, M, N. Ported from the GPU plugin.
	private void faceUvs(float[] vx, float[] vy, float[] vz, int a, int b, int c,
		byte[] textureFaces, int[] t1, int[] t2, int[] t3, int face)
	{
		if (textureFaces == null || textureFaces[face] == -1)
		{
			// The client then uses the face itself as the texture triangle.
			u[0] = 0f;
			v[0] = 0f;
			u[1] = 1f;
			v[1] = 0f;
			u[2] = 0f;
			v[2] = 1f;
			return;
		}
		int tf = textureFaces[face] & 0xff;
		int pa = t1[tf], pb = t2[tf], pc = t3[tf];

		float px = vx[pa], py = vy[pa], pz = vz[pa];
		float mx = vx[pb] - px, my = vy[pb] - py, mz = vz[pb] - pz;
		float nx = vx[pc] - px, ny = vy[pc] - py, nz = vz[pc] - pz;

		float ax = vx[a] - px, ay = vy[a] - py, az = vz[a] - pz;
		float bx = vx[b] - px, by = vy[b] - py, bz = vz[b] - pz;
		float cx = vx[c] - px, cy = vy[c] - py, cz = vz[c] - pz;

		float wx = my * nz - mz * ny;
		float wy = mz * nx - mx * nz;
		float wz = mx * ny - my * nx;

		float qx = ny * wz - nz * wy;
		float qy = nz * wx - nx * wz;
		float qz = nx * wy - ny * wx;
		float f = 1f / (qx * mx + qy * my + qz * mz);
		u[0] = (qx * ax + qy * ay + qz * az) * f;
		u[1] = (qx * bx + qy * by + qz * bz) * f;
		u[2] = (qx * cx + qy * cy + qz * cz) * f;

		qx = my * wz - mz * wy;
		qy = mz * wx - mx * wz;
		qz = mx * wy - my * wx;
		f = 1f / (qx * nx + qy * ny + qz * nz);
		v[0] = (qx * ax + qy * ay + qz * az) * f;
		v[1] = (qx * bx + qy * by + qz * bz) * f;
		v[2] = (qx * cx + qy * cy + qz * cz) * f;
	}

	// Mirrors the client's combination of model-wide and per-face transparency.
	private static int faceTransparency(int modelTransparency, int faceTransparency)
	{
		if (modelTransparency > 0 && faceTransparency < 253)
		{
			return faceTransparency + ((253 - faceTransparency) * modelTransparency >> 8);
		}
		return faceTransparency;
	}
}
