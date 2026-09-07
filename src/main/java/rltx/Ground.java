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

	/** Within this many tiles of an edge where the neighbouring tile differs, the lift fades to nothing. */
	private static final float SEAM = 0.35f;

	private final RltxConfig config;
	private final FrameParams frame;
	private int[] order = new int[64];
	private float[] distance = new float[64];
	private int[] faces = new int[64];
	/** The ground texture of every tile of the scene the meshes were cut for, by tile; 0 where there is none. */
	private StaticScene mapped;
	private int[] tileTexture;
	private int tilesZ;
	private int offsetTiles;

	Ground(RltxConfig config, FrameParams frame)
	{
		this.config = config;
		this.frame = frame;
	}

	void push(LoadedScene top, GeometryBuffer dynamic, RtRenderer renderer, boolean texturesReady)
	{
		if (!config.terrainRelief() || frame.groundRelief <= 0f || !frame.terrainTextures || !texturesReady || top == null)
		{
			renderer.setGroundRange(0, 0);
			return;
		}
		StaticScene built = top.built;
		int offsetTiles = (built.zonesX * 8 - Constants.SCENE_SIZE) / 2;
		if (mapped != built)
		{
			mapTiles(built, offsetTiles);
		}
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

	// Which ground texture each tile carries, so a tile's lift can fade where its neighbour's
	// heights would not meet it. The lowest terrain face of a tile speaks for it.
	private void mapTiles(StaticScene built, int offset)
	{
		mapped = built;
		offsetTiles = offset;
		tilesZ = built.zonesZ * 8;
		tileTexture = new int[built.zonesX * 8 * tilesZ];
		float[] tileY = new float[tileTexture.length];
		java.util.Arrays.fill(tileY, Float.NEGATIVE_INFINITY);
		for (StaticScene.Zone zone : built.zones)
		{
			if (zone == null)
			{
				continue;
			}
			float[] pos = zone.geometry.positions();
			int[] textures = zone.geometry.textures();
			for (int f = 0; f < zone.geometry.faces(); ++f)
			{
				if ((textures[f] & StaticSceneBuilder.TERRAIN_BIT) == 0)
				{
					continue;
				}
				int o = f * 9;
				int tile = tileIndex(pos[o] + pos[o + 3] + pos[o + 6], pos[o + 2] + pos[o + 5] + pos[o + 8]);
				float y = (pos[o + 1] + pos[o + 4] + pos[o + 7]) / 3f;
				// y grows downwards, so the lowest ground is the largest y.
				if (tile >= 0 && y > tileY[tile])
				{
					tileY[tile] = y;
					tileTexture[tile] = textures[f] & 0xffff;
				}
			}
		}
	}

	// The index of the tile holding a point given as the sum of three x and three z, or -1 off the grid.
	private int tileIndex(float x3, float z3)
	{
		int tx = (int) Math.floor(x3 / (3f * Perspective.LOCAL_TILE_SIZE)) + offsetTiles;
		int tz = (int) Math.floor(z3 / (3f * Perspective.LOCAL_TILE_SIZE)) + offsetTiles;
		return tileAt(tx, tz);
	}

	private int tileAt(int tx, int tz)
	{
		if (tx < 0 || tz < 0 || tz >= tilesZ || tx >= tileTexture.length / tilesZ)
		{
			return -1;
		}
		return tx * tilesZ + tz;
	}

	private boolean sameTexture(int tx, int tz, int texture)
	{
		int tile = tileAt(tx, tz);
		return tile >= 0 && tileTexture[tile] == texture;
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
	// colours ride in the first three UV slots as raw bits and are interpolated as colours; the
	// last three carry how much of the lift each vertex takes, which falls to nothing toward an
	// edge whose neighbour is another texture, water or nothing, since that side's heights would
	// not meet. Built once per zone and kept with it.
	private GeometryBuffer subdivide(StaticScene.Zone zone, int terrainFaces)
	{
		GeometryBuffer mesh = new GeometryBuffer(terrainFaces * SUBDIVISIONS * SUBDIVISIONS);
		float[] pos = zone.geometry.positions();
		int[] colors = zone.geometry.colors();
		int[] textures = zone.geometry.textures();
		float[] uvs = zone.geometry.uvs();
		int n = SUBDIVISIONS;
		// Four corners of a cell, x, y, z, the packed colour and the lift each: a triangle and the one that shares its diagonal.
		float[] corner = new float[4 * 5];
		boolean[] same = new boolean[8];
		for (int f = 0; f < zone.geometry.faces(); ++f)
		{
			if ((textures[f] & StaticSceneBuilder.TERRAIN_BIT) == 0)
			{
				continue;
			}
			int o = f * 9;
			int uo = f * 6;
			int texture = textures[f] & 0xffff;
			int tx = (int) Math.floor((pos[o] + pos[o + 3] + pos[o + 6]) / (3f * Perspective.LOCAL_TILE_SIZE));
			int tz = (int) Math.floor((pos[o + 2] + pos[o + 5] + pos[o + 8]) / (3f * Perspective.LOCAL_TILE_SIZE));
			// West, east, south, north, then the corners between them in the same order.
			same[0] = sameTexture(tx - 1 + offsetTiles, tz + offsetTiles, texture);
			same[1] = sameTexture(tx + 1 + offsetTiles, tz + offsetTiles, texture);
			same[2] = sameTexture(tx + offsetTiles, tz - 1 + offsetTiles, texture);
			same[3] = sameTexture(tx + offsetTiles, tz + 1 + offsetTiles, texture);
			same[4] = sameTexture(tx - 1 + offsetTiles, tz - 1 + offsetTiles, texture);
			same[5] = sameTexture(tx + 1 + offsetTiles, tz - 1 + offsetTiles, texture);
			same[6] = sameTexture(tx - 1 + offsetTiles, tz + 1 + offsetTiles, texture);
			same[7] = sameTexture(tx + 1 + offsetTiles, tz + 1 + offsetTiles, texture);
			for (int a = 0; a < n; ++a)
			{
				for (int b = 0; a + b < n; ++b)
				{
					vertex(pos, uvs, o, uo, a, b, n, corner, 0, tx, tz, same);
					vertex(pos, uvs, o, uo, a + 1, b, n, corner, 1, tx, tz, same);
					vertex(pos, uvs, o, uo, a, b + 1, n, corner, 2, tx, tz, same);
					triangle(mesh, corner, 0, 1, 2, colors[f], textures[f]);
					if (a + b + 1 < n)
					{
						vertex(pos, uvs, o, uo, a + 1, b + 1, n, corner, 3, tx, tz, same);
						triangle(mesh, corner, 1, 3, 2, colors[f], textures[f]);
					}
				}
			}
		}
		return mesh;
	}

	// The point a/n of the way from corner 0 to corner 1 and b/n to corner 2: x, y, z, its colour and its lift.
	private static void vertex(float[] pos, float[] uvs, int o, int uo, int a, int b, int n, float[] out, int slot, int tx, int tz, boolean[] same)
	{
		float wa = a / (float) n;
		float wb = b / (float) n;
		float w0 = 1f - wa - wb;
		int s = slot * 5;
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
		// Where the vertex sits within its tile, 0 to 1 each way, and its distance from each side.
		float fx = out[s] / Perspective.LOCAL_TILE_SIZE - tx;
		float fz = out[s + 2] / Perspective.LOCAL_TILE_SIZE - tz;
		float lift = 1f;
		lift *= same[0] ? 1f : seam(fx);
		lift *= same[1] ? 1f : seam(1f - fx);
		lift *= same[2] ? 1f : seam(fz);
		lift *= same[3] ? 1f : seam(1f - fz);
		lift *= same[4] ? 1f : seam(Math.max(fx, fz));
		lift *= same[5] ? 1f : seam(Math.max(1f - fx, fz));
		lift *= same[6] ? 1f : seam(Math.max(fx, 1f - fz));
		lift *= same[7] ? 1f : seam(Math.max(1f - fx, 1f - fz));
		out[s + 4] = lift;
	}

	// Smoothly from nothing at an edge to full a seam's width in.
	private static float seam(float distance)
	{
		float t = Math.min(Math.max(distance / SEAM, 0f), 1f);
		return t * t * (3f - 2f * t);
	}

	private static void triangle(GeometryBuffer mesh, float[] c, int i, int j, int k, int color, int texture)
	{
		int a = i * 5, b = j * 5, d = k * 5;
		mesh.face(c[a], c[a + 1], c[a + 2], c[b], c[b + 1], c[b + 2], c[d], c[d + 1], c[d + 2], color, texture,
			c[a + 3], c[b + 3], c[d + 3], c[a + 4], c[b + 4], c[d + 4]);
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
