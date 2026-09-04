package rltx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CameraMathTest
{
	// Ports of the GPU plugin's Mat4 rotations, reduced to 3x3 row-major.
	private static float[] rotateX(float rx)
	{
		float s = (float) Math.sin(rx);
		float c = (float) Math.cos(rx);
		return new float[]{
			1, 0, 0,
			0, c, -s,
			0, s, c,
		};
	}

	private static float[] rotateY(float ry)
	{
		float s = (float) Math.sin(ry);
		float c = (float) Math.cos(ry);
		return new float[]{
			c, 0, s,
			0, 1, 0,
			-s, 0, c,
		};
	}

	private static float[] mul(float[] a, float[] b)
	{
		float[] r = new float[9];
		for (int i = 0; i < 3; ++i)
		{
			for (int j = 0; j < 3; ++j)
			{
				r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
			}
		}
		return r;
	}

	private static float[] apply(float[] m, float x, float y, float z)
	{
		return new float[]{
			m[0] * x + m[1] * y + m[2] * z,
			m[3] * x + m[4] * y + m[5] * z,
			m[6] * x + m[7] * y + m[8] * z,
		};
	}

	@Test
	public void inverseUndoesVanillaRotation()
	{
		float[] pitches = {0f, 0.3f, 0.9f, 1.4f};
		float[] yaws = {0f, 0.7f, 2.1f, 4.5f, 6.0f};
		for (float pitch : pitches)
		{
			for (float yaw : yaws)
			{
				float[] forward = mul(rotateX(pitch), rotateY(yaw));
				float[] inverse = new float[9];
				CameraMath.inverseRotation(pitch, yaw, inverse);
				float[] identity = mul(forward, inverse);
				for (int i = 0; i < 9; ++i)
				{
					float expected = i % 4 == 0 ? 1f : 0f;
					assertEquals("pitch " + pitch + " yaw " + yaw + " element " + i, expected, identity[i], 1e-5f);
				}
			}
		}
	}

	@Test
	public void forwardMatchesVanillaRotation()
	{
		float[] pitches = {0f, 0.3f, 0.9f, 1.4f};
		float[] yaws = {0f, 0.7f, 2.1f, 4.5f, 6.0f};
		for (float pitch : pitches)
		{
			for (float yaw : yaws)
			{
				float[] expected = mul(rotateX(pitch), rotateY(yaw));
				float[] actual = new float[9];
				CameraMath.forwardRotation(pitch, yaw, actual);
				for (int i = 0; i < 9; ++i)
				{
					assertEquals("pitch " + pitch + " yaw " + yaw + " element " + i, expected[i], actual[i], 1e-6f);
				}
			}
		}
	}

	@Test
	public void pixelRayPassesThroughProjectedPoint()
	{
		float zoom = 640f;
		float w = 1200f, h = 800f;
		float cx = 6000f, cy = -900f, cz = 7000f;
		float[] pitches = {0.2f, 0.6f, 1.2f};
		float[] yaws = {0f, 0.9f, 2.3f, 3.8f, 5.5f};
		float[][] viewPoints = {{300f, -150f, 2000f}, {-800f, 400f, 900f}, {0f, 0f, 5000f}};

		for (float pitch : pitches)
		{
			for (float yaw : yaws)
			{
				float[] forward = mul(rotateX(pitch), rotateY(yaw));
				for (float[] vp : viewPoints)
				{
					// A rotation's inverse is its transpose, so this world point is
					// derived without touching the code under test.
					float[] offset = apply(transpose(forward), vp[0], vp[1], vp[2]);
					float px = cx + offset[0], py = cy + offset[1], pz = cz + offset[2];

					// Vanilla projection: pixel = viewport centre + view.xy * zoom / view.z.
					float[] view = apply(forward, px - cx, py - cy, pz - cz);
					assertTrue(view[2] > 0);
					float sx = w / 2 + view[0] * zoom / view[2];
					float sy = h / 2 + view[1] * zoom / view[2];

					float[] inverse = new float[9];
					CameraMath.inverseRotation(pitch, yaw, inverse);
					float[] dir = apply(inverse, (sx - w / 2) / zoom, (sy - h / 2) / zoom, 1f);

					float dx = px - cx, dy = py - cy, dz = pz - cz;
					float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
					float dlen = (float) Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
					String label = "pitch " + pitch + " yaw " + yaw;
					assertEquals(label, dx / len, dir[0] / dlen, 1e-4f);
					assertEquals(label, dy / len, dir[1] / dlen, 1e-4f);
					assertEquals(label, dz / len, dir[2] / dlen, 1e-4f);
				}
			}
		}
	}

	private static float[] transpose(float[] m)
	{
		return new float[]{
			m[0], m[3], m[6],
			m[1], m[4], m[7],
			m[2], m[5], m[8],
		};
	}
}
