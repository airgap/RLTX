package rltx;

import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import rltx.scene.GeometryBuffer;
import rltx.scene.StaticScene;
import rltx.scene.StaticSceneBuilder;
import rltx.vk.FrameParams;
import rltx.vk.RtRenderer;

/**
 * Ground near the camera as relief: each zone's terrain faces go through the dynamic path cut
 * into a fine grid, and the GPU lifts every vertex by its ground texture's height map before the
 * acceleration structure is built, so cobbles and stones stand up out of the tile. The flat
 * static ground stays underneath, hidden by the lifted copy, which only ever rises.
 */
final class Ground
{
	/** Zones whose nearest edge lies within this of the camera are lifted; the shader fades the lift to nothing at it. */
	static final float RANGE = 12 * Perspective.LOCAL_TILE_SIZE;
	private static final float ZONE_SIZE = 8 * Perspective.LOCAL_TILE_SIZE;
	private static final int FACE_BUDGET = 60_000;
	/** Each terrain triangle is cut this many times along each edge; a tile's two become thirty-two. */
	static final int SUBDIVISIONS = 4;

	private final RltxConfig config;
	private final FrameParams frame;
	private int[] order = new int[64];
	private float[] distance = new float[64];
	private int[] faces = new int[64];

	Ground(RltxConfig config, FrameParams frame)
	{
		this.config = config;
		this.frame = frame;
	}

	void push(LoadedScene top, GeometryBuffer dynamic, RtRenderer renderer, boolean texturesReady)
	{
		if (!config.terrainRelief() || !frame.terrainTextures || !texturesReady || top == null)
		{
			renderer.setGroundRange(0, 0);
			return;
		}
		StaticScene built = top.built;
		int offsetTiles = (built.zonesX * 8 - Constants.SCENE_SIZE) / 2;
		int candidates = 0;
		for (int i = 0; i < built.zones.length; ++i)
		{
			StaticScene.Zone zone = built.zones[i];
			if (zone == null)
			{
				continue;
			}
			float minX = ((i / built.zonesZ) * 8 - offsetTiles) * Perspective.LOCAL_TILE_SIZE;
			float minZ = ((i % built.zonesZ) * 8 - offsetTiles) * Perspective.LOCAL_TILE_SIZE;
			float dx = Math.max(0f, Math.max(minX - frame.cameraX, frame.cameraX - (minX + ZONE_SIZE)));
			float dz = Math.max(0f, Math.max(minZ - frame.cameraZ, frame.cameraZ - (minZ + ZONE_SIZE)));
			float nearest = (float) Math.sqrt(dx * dx + dz * dz);
			if (nearest > RANGE)
			{
				continue;
			}
			int terrainFaces = terrainFaces(zone);
			if (terrainFaces == 0)
			{
				continue;
			}
			if (candidates == order.length)
			{
				order = java.util.Arrays.copyOf(order, order.length * 2);
				distance = java.util.Arrays.copyOf(distance, distance.length * 2);
				faces = java.util.Arrays.copyOf(faces, faces.length * 2);
			}
			order[candidates] = i;
			distance[candidates] = nearest;
			faces[candidates] = terrainFaces;
			++candidates;
		}
		int first = dynamic.faces();
		int budget = FACE_BUDGET;
		// Nearest zones first, so what the budget leaves flat is the farthest ground.
		for (int k = 0; k < candidates; ++k)
		{
			int best = k;
			for (int j = k + 1; j < candidates; ++j)
			{
				if (distance[j] < distance[best])
				{
					best = j;
				}
			}
			swap(order, k, best);
			swap(distance, k, best);
			swap(faces, k, best);
			int meshFaces = faces[k] * SUBDIVISIONS * SUBDIVISIONS;
			if (budget < meshFaces)
			{
				break;
			}
			StaticScene.Zone zone = built.zones[order[k]];
			budget -= meshFaces;
			if (zone.groundMesh == null)
			{
				zone.groundMesh = subdivide(zone, faces[k]);
			}
			dynamic.append(zone.groundMesh);
		}
		renderer.setGroundRange(first, dynamic.faces() - first);
	}

	private static int terrainFaces(StaticScene.Zone zone)
	{
		int[] textures = zone.geometry.textures();
		int count = 0;
		for (int f = 0; f < zone.geometry.faces(); ++f)
		{
			if ((textures[f] & StaticSceneBuilder.TERRAIN_BIT) != 0)
			{
				++count;
			}
		}
		return count;
	}

	// The zone's terrain faces, each cut into a grid of smaller triangles. The three vertex
	// colours ride in the UV slots as raw bits and are interpolated as colours; built once per
	// zone and kept with it.
	private static GeometryBuffer subdivide(StaticScene.Zone zone, int terrainFaces)
	{
		GeometryBuffer mesh = new GeometryBuffer(terrainFaces * SUBDIVISIONS * SUBDIVISIONS);
		float[] pos = zone.geometry.positions();
		int[] colors = zone.geometry.colors();
		int[] textures = zone.geometry.textures();
		float[] uvs = zone.geometry.uvs();
		int n = SUBDIVISIONS;
		// Four corners of a cell, x, y, z and the packed colour each: a triangle and the one that shares its diagonal.
		float[] corner = new float[4 * 4];
		for (int f = 0; f < zone.geometry.faces(); ++f)
		{
			if ((textures[f] & StaticSceneBuilder.TERRAIN_BIT) == 0)
			{
				continue;
			}
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
		return mesh;
	}

	// The point a/n of the way from corner 0 to corner 1 and b/n to corner 2: x, y, z and its colour.
	private static void vertex(float[] pos, float[] uvs, int o, int uo, int a, int b, int n, float[] out, int slot)
	{
		float wa = a / (float) n;
		float wb = b / (float) n;
		float w0 = 1f - wa - wb;
		int s = slot * 4;
		for (int c = 0; c < 3; ++c)
		{
			out[s + c] = w0 * pos[o + c] + wa * pos[o + 3 + c] + wb * pos[o + 6 + c];
		}
		int c0 = Float.floatToRawIntBits(uvs[uo]);
		int c1 = Float.floatToRawIntBits(uvs[uo + 1]);
		int c2 = Float.floatToRawIntBits(uvs[uo + 2]);
		int packed = 0;
		for (int shift = 0; shift < 24; shift += 8)
		{
			float channel = w0 * (c0 >> shift & 0xff) + wa * (c1 >> shift & 0xff) + wb * (c2 >> shift & 0xff);
			packed |= Math.round(channel) << shift;
		}
		out[s + 3] = Float.intBitsToFloat(packed);
	}

	private static void triangle(GeometryBuffer mesh, float[] c, int i, int j, int k, int color, int texture)
	{
		int a = i * 4, b = j * 4, d = k * 4;
		mesh.face(c[a], c[a + 1], c[a + 2], c[b], c[b + 1], c[b + 2], c[d], c[d + 1], c[d + 2], color, texture,
			c[a + 3], c[b + 3], c[d + 3], 0f, 0f, 0f);
	}

	private static void swap(int[] a, int i, int j)
	{
		int t = a[i];
		a[i] = a[j];
		a[j] = t;
	}

	private static void swap(float[] a, int i, int j)
	{
		float t = a[i];
		a[i] = a[j];
		a[j] = t;
	}
}
