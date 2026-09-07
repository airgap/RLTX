package rltx;

import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import rltx.scene.GeometryBuffer;
import rltx.scene.StaticScene;
import rltx.vk.FrameParams;
import rltx.vk.RtRenderer;

/**
 * Water near the camera as real geometry: its faces go through the dynamic path each frame, cut
 * finely enough to carry ripples a few units across, and the GPU lifts every vertex by the waves
 * and the simulated ripples before the acceleration structure is built. The static water group
 * of each zone drawn this way is skipped.
 */
final class Waves
{
	private static final float WATER_RANGE = 14 * Perspective.LOCAL_TILE_SIZE;
	private static final int WATER_FACE_BUDGET = 60_000;
	/** Each water triangle is cut this many times along each edge; a tile's two become thirty-two. */
	private static final int SUBDIVISIONS = 4;

	private final RltxConfig config;
	private final FrameParams frame;

	Waves(RltxConfig config, FrameParams frame)
	{
		this.config = config;
		this.frame = frame;
	}

	void push(LoadedScene top, GeometryBuffer dynamicWater, RtRenderer renderer)
	{
		if (!config.waveGeometry() || !frame.water || top == null)
		{
			renderer.setDisplacedZones(WorldView.TOPLEVEL, null);
			return;
		}
		StaticScene built = top.built;
		if (top.displaced == null || top.displaced.length != built.zones.length)
		{
			top.displaced = new boolean[built.zones.length];
		}
		int offsetTiles = (built.zonesX * 8 - Constants.SCENE_SIZE) / 2;
		int budget = WATER_FACE_BUDGET;
		for (int i = 0; i < built.zones.length; ++i)
		{
			StaticScene.Zone zone = built.zones[i];
			top.displaced[i] = false;
			if (zone == null)
			{
				continue;
			}
			int waterFaces = 0;
			for (int g = 0; g < zone.groupWater.length; ++g)
			{
				waterFaces += zone.groupWater[g] ? zone.groupFaceCount[g] : 0;
			}
			int meshFaces = waterFaces * SUBDIVISIONS * SUBDIVISIONS;
			if (waterFaces == 0 || budget < meshFaces)
			{
				continue;
			}
			float centreX = ((i / built.zonesZ) * 8 - offsetTiles + 4) * Perspective.LOCAL_TILE_SIZE;
			float centreZ = ((i % built.zonesZ) * 8 - offsetTiles + 4) * Perspective.LOCAL_TILE_SIZE;
			float dx = centreX - frame.cameraX;
			float dz = centreZ - frame.cameraZ;
			if (dx * dx + dz * dz > WATER_RANGE * WATER_RANGE)
			{
				continue;
			}
			top.displaced[i] = true;
			budget -= meshFaces;
			if (zone.waterMesh == null)
			{
				zone.waterMesh = subdivide(zone, waterFaces);
			}
			dynamicWater.append(zone.waterMesh);
		}
		renderer.setDisplacedZones(WorldView.TOPLEVEL, top.displaced);
	}

	// The zone's water faces, flat, each cut into a grid of smaller triangles with the texture
	// coordinates interpolated; built once per zone and kept with it.
	private static GeometryBuffer subdivide(StaticScene.Zone zone, int waterFaces)
	{
		GeometryBuffer mesh = new GeometryBuffer(waterFaces * SUBDIVISIONS * SUBDIVISIONS);
		float[] pos = zone.geometry.positions();
		int[] colors = zone.geometry.colors();
		int[] textures = zone.geometry.textures();
		float[] uvs = zone.geometry.uvs();
		int n = SUBDIVISIONS;
		// Four corners of a cell, x, y, z, u and v each: a triangle and the one that shares its diagonal.
		float[] corner = new float[5 * 4];
		for (int g = 0; g < zone.groupWater.length; ++g)
		{
			if (!zone.groupWater[g])
			{
				continue;
			}
			for (int f = zone.groupFaceBase[g]; f < zone.groupFaceBase[g] + zone.groupFaceCount[g]; ++f)
			{
				int o = f * 9;
				int uo = f * 6;
				for (int a = 0; a < n; ++a)
				{
					for (int b = 0; a + b < n; ++b)
					{
						vertex(pos, uvs, o, uo, a, b, n, corner, 0);
						vertex(pos, uvs, o, uo, a + 1, b, n, corner, 1);
						vertex(pos, uvs, o, uo, a, b + 1, n, corner, 2);
						triangle(mesh, corner, 0, 1, 2, colors[f], textures[f]);
						if (a + b + 1 < n)
						{
							vertex(pos, uvs, o, uo, a + 1, b + 1, n, corner, 3);
							triangle(mesh, corner, 1, 3, 2, colors[f], textures[f]);
						}
					}
				}
			}
		}
		return mesh;
	}

	// The point a/n of the way from corner 0 to corner 1 and b/n to corner 2: x, y, z, u, v.
	private static void vertex(float[] pos, float[] uvs, int o, int uo, int a, int b, int n, float[] out, int slot)
	{
		float wa = a / (float) n;
		float wb = b / (float) n;
		float w0 = 1f - wa - wb;
		int s = slot * 5;
		for (int c = 0; c < 3; ++c)
		{
			out[s + c] = w0 * pos[o + c] + wa * pos[o + 3 + c] + wb * pos[o + 6 + c];
		}
		out[s + 3] = w0 * uvs[uo] + wa * uvs[uo + 2] + wb * uvs[uo + 4];
		out[s + 4] = w0 * uvs[uo + 1] + wa * uvs[uo + 3] + wb * uvs[uo + 5];
	}

	private static void triangle(GeometryBuffer mesh, float[] c, int i, int j, int k, int color, int texture)
	{
		int a = i * 5, b = j * 5, d = k * 5;
		mesh.face(c[a], c[a + 1], c[a + 2], c[b], c[b + 1], c[b + 2], c[d], c[d + 1], c[d + 2], color, texture,
			c[a + 3], c[a + 4], c[b + 3], c[b + 4], c[d + 3], c[d + 4]);
	}
}
