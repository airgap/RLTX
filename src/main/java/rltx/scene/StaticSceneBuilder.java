package rltx.scene;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.client.callback.RenderCallbackManager;

/**
 * Walks a scene zone by zone and buckets every static triangle by (render level, roof id,
 * translucency), following the grouping the GPU plugin's SceneUploader uses so the same
 * per-frame visibility rules apply. The top-level scene carries a margin of extended tiles
 * around the 104 by 104 playable area; nested world views do not.
 */
public final class StaticSceneBuilder
{
	private static final int TOPLEVEL_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	private static final int HIDDEN_COLOR = 12345678;
	/** Marks an untextured terrain face; its texture id names a ground detail layer. */
	public static final int TERRAIN_BIT = 1 << 30;

	// Untextured terrain faces: the face colour stays flat as before, the three vertex colours
	// travel in the UV slots as raw bits for the smoothing option, and the ground detail chosen
	// by colour becomes the texture id. UVs are not needed since the detail tiles in world space.
	private static void terrainFace(GeometryBuffer out, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, int c0, int c1, int c2)
	{
		int rgba = average(c0, c1, c2);
		int texture = (GroundTextures.classify(rgba).layer() + 1) | TERRAIN_BIT;
		out.face(x0, y0, z0, x1, y1, z1, x2, y2, z2, rgba, texture,
			Float.intBitsToFloat(c0 & 0xffffff), Float.intBitsToFloat(c1 & 0xffffff), Float.intBitsToFloat(c2 & 0xffffff), 0f, 0f, 0f);
	}

	private final Scene scene;
	private final RenderCallbackManager renderCallbacks;
	private final Palette palette;
	private final int offset;
	private final int[][][] terrainLight;
	private final WaterBed waterBed;
	private final IntUnaryOperator foliageKind;
	private final float treeScale;
	private final IntPredicate lit;
	private final ModelPusher pusher = new ModelPusher();

	private static final class Bucket
	{
		final GeometryBuffer opaque = new GeometryBuffer(256);
		final GeometryBuffer translucent = new GeometryBuffer(32);
		final GeometryBuffer water = new GeometryBuffer(16);
		final GeometryBuffer sway = new GeometryBuffer(64);
		float[] swayWeights = new float[64 * 3];
	}

	private StaticSceneBuilder(Scene scene, RenderCallbackManager renderCallbacks, Palette palette, int[][][] terrainLight, WaterBed waterBed, IntUnaryOperator foliageKind, float treeScale, IntPredicate lit)
	{
		this.scene = scene;
		this.renderCallbacks = renderCallbacks;
		this.palette = palette;
		this.offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? TOPLEVEL_OFFSET : 0;
		this.terrainLight = terrainLight;
		this.waterBed = waterBed;
		this.foliageKind = foliageKind;
		this.treeScale = treeScale;
		this.lit = lit;
	}

	/** Number of zones along each axis of the scene's extended tile grid. */
	public static int zoneCount(Scene scene)
	{
		return scene.getExtendedTiles()[0].length >> 3;
	}

	/**
	 * Builds every zone of the scene.
	 *
	 * @param foliageKind 0 for rigid objects, 1 for foliage that sways, 2 for trees that sway and scale
	 * @param treeScale   visual size multiplier for trees, about their base
	 * @param lit         which object ids carry a light, so their hot faces are flames
	 */
	public static StaticScene build(Scene scene, RenderCallbackManager renderCallbacks, Palette palette, IntUnaryOperator foliageKind, float treeScale, IntPredicate lit)
	{
		int[][][] light = terrainLight(scene, palette);
		WaterBed bed = waterBed(scene);
		int zones = zoneCount(scene);
		StaticScene.Zone[] out = new StaticScene.Zone[zones * zones];
		StaticSceneBuilder builder = new StaticSceneBuilder(scene, renderCallbacks, palette, light, bed, foliageKind, treeScale, lit);
		for (int zx = 0; zx < zones; ++zx)
		{
			for (int zz = 0; zz < zones; ++zz)
			{
				out[zx * zones + zz] = builder.zone(zx, zz);
			}
		}
		return new StaticScene(zones, zones, out);
	}

	/** Rebuilds a single zone, for example after a door changed. */
	public static StaticScene.Zone buildZone(Scene scene, int zx, int zz, RenderCallbackManager renderCallbacks, Palette palette, int[][][] terrainLight, WaterBed waterBed, IntUnaryOperator foliageKind, float treeScale, IntPredicate lit)
	{
		return new StaticSceneBuilder(scene, renderCallbacks, palette, terrainLight, waterBed, foliageKind, treeScale, lit).zone(zx, zz);
	}

	/** Distinct ids of the game objects in the scene, for classifying foliage before a build. */
	public static Set<Integer> gameObjectIds(Scene scene)
	{
		Set<Integer> ids = new HashSet<>();
		for (Tile[][] plane : scene.getExtendedTiles())
		{
			for (Tile[] column : plane)
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					for (GameObject go : tile.getGameObjects())
					{
						if (go != null)
						{
							ids.add(go.getId());
						}
					}
				}
			}
		}
		return ids;
	}

	// 117 HD's underwater slope: depth by tiles from the shore, scaled as its renderer does.
	private static final int[] BED_SLOPE = {150, 300, 470, 610, 700, 750, 820, 920, 1080, 1300, 1350, 1380};
	private static final float BED_SCALE = 0.55f;
	private static final int BED_MIN_DEPTH = 6;

	private static final int MIST_REACH_TILES = 6;
	/** Floats per grid vertex in {@link #mistGrid}: ground height, mist coverage, puddle coverage, unused. */
	public static final int MIST_FLOATS = 4;

	/**
	 * The ground grid of the lowest plane: height at each vertex, a mist coverage that is 1
	 * over swamp water and around objects that call for mist, such as graves, fading out over a
	 * few tiles, and a puddle coverage where the ground dips below its surroundings.
	 *
	 * @param misty object ids around which mist gathers
	 */
	public static float[] mistGrid(Scene scene, IntPredicate misty)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		int size = tiles[0].length;
		int[][] heights = scene.getTileHeights()[0];
		int[][] distance = new int[size + 1][size + 1];
		for (int[] row : distance)
		{
			java.util.Arrays.fill(row, MIST_REACH_TILES + 1);
		}
		for (int x = 0; x < size; ++x)
		{
			for (int y = 0; y < size; ++y)
			{
				Tile t = tiles[0][x][y];
				if (t == null)
				{
					continue;
				}
				boolean seed = t.getSceneTilePaint() != null && WaterType.forTexture(t.getSceneTilePaint().getTexture()) == WaterType.SWAMP_WATER_FLAT;
				for (GameObject go : t.getGameObjects())
				{
					seed |= go != null && misty.test(go.getId());
				}
				if (!seed)
				{
					continue;
				}
				distance[x][y] = 0;
				distance[x + 1][y] = 0;
				distance[x][y + 1] = 0;
				distance[x + 1][y + 1] = 0;
			}
		}
		for (int pass = 0; pass < MIST_REACH_TILES; ++pass)
		{
			for (int vx = 0; vx <= size; ++vx)
			{
				for (int vy = 0; vy <= size; ++vy)
				{
					int nearest = distance[vx][vy];
					if (vx > 0) nearest = Math.min(nearest, distance[vx - 1][vy] + 1);
					if (vy > 0) nearest = Math.min(nearest, distance[vx][vy - 1] + 1);
					if (vx < size) nearest = Math.min(nearest, distance[vx + 1][vy] + 1);
					if (vy < size) nearest = Math.min(nearest, distance[vx][vy + 1] + 1);
					distance[vx][vy] = nearest;
				}
			}
		}
		// A vertex lower than the mean of its neighbours by more than a step is a dip (y grows
		// downwards); its neighbours get half coverage so puddles have soft edges.
		float[] puddle = new float[(size + 1) * (size + 1)];
		for (int vx = 1; vx < size; ++vx)
		{
			for (int vy = 1; vy < size; ++vy)
			{
				float mean = (heights[vx - 1][vy] + heights[vx + 1][vy] + heights[vx][vy - 1] + heights[vx][vy + 1]) * 0.25f;
				float dip = heights[vx][vy] - mean;
				float cover = Math.max(0f, Math.min(1f, (dip - 1.5f) / 6f));
				if (cover > 0f)
				{
					int i = vx * (size + 1) + vy;
					puddle[i] = Math.max(puddle[i], cover);
					puddle[i - 1] = Math.max(puddle[i - 1], cover * 0.5f);
					puddle[i + 1] = Math.max(puddle[i + 1], cover * 0.5f);
					puddle[i - (size + 1)] = Math.max(puddle[i - (size + 1)], cover * 0.5f);
					puddle[i + (size + 1)] = Math.max(puddle[i + (size + 1)], cover * 0.5f);
				}
			}
		}
		float[] grid = new float[(size + 1) * (size + 1) * MIST_FLOATS];
		for (int vx = 0; vx <= size; ++vx)
		{
			for (int vy = 0; vy <= size; ++vy)
			{
				int o = (vx * (size + 1) + vy) * MIST_FLOATS;
				grid[o] = heights[vx][vy];
				grid[o + 1] = Math.max(0f, 1f - distance[vx][vy] / (float) (MIST_REACH_TILES + 1));
				grid[o + 2] = puddle[vx * (size + 1) + vy];
			}
		}
		return grid;
	}

	/** A bed colour entry naming a texture rather than a colour. */
	private static final int BED_TEXTURE_FLAG = 1 << 30;
	private static final int BED_NO_COLOR = -1;

	/**
	 * The bed synthesised under water, per plane and grid vertex: its depth below the surface,
	 * and its colour carried in from the nearest shoreline so shallows read as the ground
	 * continuing under the water rather than as the water tile's own colour.
	 */
	public static final class WaterBed
	{
		final int[][][] depth;
		/** HSL of the shore terrain, or a texture id flagged with {@link #BED_TEXTURE_FLAG}, or -1. */
		final int[][][] color;

		WaterBed(int[][][] depth, int[][][] color)
		{
			this.depth = depth;
			this.color = color;
		}
	}

	/**
	 * The client's water is a flat plane with nothing beneath, so a bed that deepens away from
	 * the shore gives light refracting through the surface something to land on.
	 */
	public static WaterBed waterBed(Scene scene)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		int size = tiles[0].length;
		int[][][] depth = new int[tiles.length][size + 1][size + 1];
		int[][][] color = new int[tiles.length][size + 1][size + 1];
		for (int level = 0; level < tiles.length; ++level)
		{
			boolean[][] water = new boolean[size][size];
			int[][] shore = color[level];
			for (int[] row : shore)
			{
				java.util.Arrays.fill(row, BED_NO_COLOR);
			}
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					Tile t = tiles[level][x][y];
					SceneTilePaint paint = t == null ? null : t.getSceneTilePaint();
					water[x][y] = paint != null && WaterTextures.isWater(paint.getTexture());
					if (paint == null || water[x][y] || paint.getNeColor() == HIDDEN_COLOR)
					{
						continue;
					}
					// Land: its corners seed the colours that spread under neighbouring water.
					if (paint.getTexture() != -1)
					{
						int textured = BED_TEXTURE_FLAG | paint.getTexture();
						seedShore(shore, x, y, textured, textured, textured, textured);
					}
					else
					{
						seedShore(shore, x, y, paint.getSwColor(), paint.getSeColor(), paint.getNwColor(), paint.getNeColor());
					}
				}
			}
			int[][] d = depth[level];
			for (int vx = 0; vx <= size; ++vx)
			{
				for (int vy = 0; vy <= size; ++vy)
				{
					boolean surrounded = true;
					for (int dx = -1; dx <= 0 && surrounded; ++dx)
					{
						for (int dy = -1; dy <= 0; ++dy)
						{
							int tx = vx + dx;
							int ty = vy + dy;
							if (tx < 0 || ty < 0 || tx >= size || ty >= size || !water[tx][ty])
							{
								surrounded = false;
								break;
							}
						}
					}
					d[vx][vy] = surrounded ? BED_SLOPE.length : 0;
				}
			}
			// Distance in tiles to the shore, capped where the bed stops deepening.
			for (int pass = 0; pass < BED_SLOPE.length; ++pass)
			{
				for (int vx = 0; vx <= size; ++vx)
				{
					for (int vy = 0; vy <= size; ++vy)
					{
						if (d[vx][vy] == 0)
						{
							continue;
						}
						int nearest = d[vx][vy];
						if (vx > 0) nearest = Math.min(nearest, d[vx - 1][vy] + 1);
						if (vy > 0) nearest = Math.min(nearest, d[vx][vy - 1] + 1);
						if (vx < size) nearest = Math.min(nearest, d[vx + 1][vy] + 1);
						if (vy < size) nearest = Math.min(nearest, d[vx][vy + 1] + 1);
						d[vx][vy] = nearest;
					}
				}
			}
			// Colours flow outward from the shore, each vertex taking a neighbour one step nearer.
			for (int step = 1; step <= BED_SLOPE.length; ++step)
			{
				for (int vx = 0; vx <= size; ++vx)
				{
					for (int vy = 0; vy <= size; ++vy)
					{
						if (d[vx][vy] != step || shore[vx][vy] != BED_NO_COLOR)
						{
							continue;
						}
						if (vx > 0 && d[vx - 1][vy] == step - 1 && shore[vx - 1][vy] != BED_NO_COLOR) shore[vx][vy] = shore[vx - 1][vy];
						else if (vy > 0 && d[vx][vy - 1] == step - 1 && shore[vx][vy - 1] != BED_NO_COLOR) shore[vx][vy] = shore[vx][vy - 1];
						else if (vx < size && d[vx + 1][vy] == step - 1 && shore[vx + 1][vy] != BED_NO_COLOR) shore[vx][vy] = shore[vx + 1][vy];
						else if (vy < size && d[vx][vy + 1] == step - 1 && shore[vx][vy + 1] != BED_NO_COLOR) shore[vx][vy] = shore[vx][vy + 1];
					}
				}
			}
			for (int vx = 0; vx <= size; ++vx)
			{
				for (int vy = 0; vy <= size; ++vy)
				{
					d[vx][vy] = d[vx][vy] == 0 ? BED_MIN_DEPTH : (int) (BED_SLOPE[d[vx][vy] - 1] * BED_SCALE);
				}
			}
		}
		return new WaterBed(depth, color);
	}

	private static void seedShore(int[][] shore, int x, int y, int sw, int se, int nw, int ne)
	{
		if (shore[x][y] == BED_NO_COLOR) shore[x][y] = sw;
		if (shore[x + 1][y] == BED_NO_COLOR) shore[x + 1][y] = se;
		if (shore[x][y + 1] == BED_NO_COLOR) shore[x][y + 1] = nw;
		if (shore[x + 1][y + 1] == BED_NO_COLOR) shore[x + 1][y + 1] = ne;
	}

	// The bed's colour at a grid vertex: the shore terrain carried under the water, or the
	// water tile's own colour where no shore colour reached.
	private int bedColor(int plane, int gx, int gy, int fallback)
	{
		int[][] shore = waterBed.color[plane];
		int x = Math.max(0, Math.min(shore.length - 1, gx));
		int y = Math.max(0, Math.min(shore.length - 1, gy));
		int v = shore[x][y];
		if (v == BED_NO_COLOR)
		{
			return fallback;
		}
		if ((v & BED_TEXTURE_FLAG) != 0)
		{
			return palette.texture(v & ~BED_TEXTURE_FLAG);
		}
		return cornerColor(v, plane, x, y);
	}

	/**
	 * The vanilla terrain light per plane and grid point, needed to divide the baked terrain
	 * shading out of tile colours, or null when that shading is kept.
	 */
	public static int[][][] terrainLight(Scene scene, Palette palette)
	{
		return palette.undoShading ? computeTerrainLight(scene.getTileHeights()) : null;
	}

	// The client lights each terrain grid point from the slope of the height field around it,
	// using a fixed light direction; the same formula here lets that shading be divided out.
	private static int[][][] computeTerrainLight(int[][][] heights)
	{
		int planes = heights.length;
		int size = heights[0].length;
		int[][][] light = new int[planes][size][size];
		for (int z = 0; z < planes; ++z)
		{
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					int dx = heights[z][Math.min(x + 1, size - 1)][y] - heights[z][Math.max(x - 1, 0)][y];
					int dy = heights[z][x][Math.min(y + 1, size - 1)] - heights[z][x][Math.max(y - 1, 0)];
					int len = (int) Math.sqrt(dx * dx + 65536 + dy * dy);
					int nx = (dx << 8) / len;
					int ny = 65536 / len;
					int nz = (dy << 8) / len;
					light[z][x][y] = (nx * -50 + ny * -10 + nz * -50) / 256 + 96;
				}
			}
		}
		return light;
	}

	private StaticScene.Zone zone(int zx, int zz)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		byte[][][] settings = scene.getExtendedTileSettings();
		int[][][] roofs = scene.getRoofs();
		Map<Long, Bucket> groups = new LinkedHashMap<>();

		for (int level = 0; level <= 3; ++level)
		{
			for (int msx = zx << 3; msx < (zx + 1) << 3; ++msx)
			{
				for (int msz = zz << 3; msz < (zz + 1) << 3; ++msz)
				{
					Tile t = tiles[level][msx][msz];
					if (t == null)
					{
						continue;
					}
					boolean bridge = (settings[1][msx][msz] & Constants.TILE_FLAG_BRIDGE) != 0;
					int mapLevel = bridge ? level + 1 : level;
					boolean visBelow = mapLevel <= 3 && (settings[mapLevel][msx][msz] & Constants.TILE_FLAG_VIS_BELOW) != 0;
					int roofId = visBelow || mapLevel == 0 ? 0 : roofs[mapLevel - 1][msx][msz];
					int groupLevel = visBelow ? 0 : level;
					long key = (long) groupLevel << 32 | (roofId & 0xffffffffL);
					uploadTile(t, groups.computeIfAbsent(key, k -> new Bucket()));
				}
			}
		}

		int total = 0;
		int count = 0;
		int swayFaces = 0;
		for (Bucket b : groups.values())
		{
			total += b.opaque.faces() + b.translucent.faces() + b.water.faces() + b.sway.faces();
			count += (b.opaque.faces() > 0 ? 1 : 0) + (b.translucent.faces() > 0 ? 1 : 0) + (b.water.faces() > 0 ? 1 : 0) + (b.sway.faces() > 0 ? 1 : 0);
			swayFaces += b.sway.faces();
		}
		if (count == 0)
		{
			return null;
		}

		GeometryBuffer all = new GeometryBuffer(total);
		int[] level = new int[count];
		int[] roof = new int[count];
		int[] base = new int[count];
		int[] faces = new int[count];
		boolean[] translucent = new boolean[count];
		boolean[] water = new boolean[count];
		boolean[] swayGroup = new boolean[count];
		GeometryBuffer sway = new GeometryBuffer(Math.max(swayFaces, 1));
		float[] swayWeights = new float[Math.max(swayFaces, 1) * 3];
		int i = 0;
		for (Map.Entry<Long, Bucket> e : groups.entrySet())
		{
			Bucket b = e.getValue();
			for (int pass = 0; pass < 4; ++pass)
			{
				GeometryBuffer g = pass == 0 ? b.opaque : pass == 1 ? b.translucent : pass == 2 ? b.water : b.sway;
				if (g.faces() == 0)
				{
					continue;
				}
				level[i] = (int) (e.getKey() >> 32);
				roof[i] = (int) (e.getKey() & 0xffffffffL);
				base[i] = all.faces();
				faces[i] = g.faces();
				translucent[i] = pass == 1;
				water[i] = pass == 2;
				swayGroup[i] = pass == 3;
				if (pass == 3)
				{
					System.arraycopy(b.swayWeights, 0, swayWeights, sway.faces() * 3, g.faces() * 3);
					sway.append(g);
				}
				all.append(g);
				++i;
			}
		}
		return new StaticScene.Zone(zx, zz, all, level, roof, base, faces, translucent, water, swayGroup, sway, swayWeights);
	}

	private void uploadTile(Tile t, Bucket bucket)
	{
		boolean drawTile = renderCallbacks.drawTile(scene, t);

		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null && drawTile)
		{
			Point p = t.getSceneLocation();
			uploadPaint(paint, t.getRenderLevel(), p.getX(), p.getY(), bucket);
		}

		SceneTileModel model = t.getSceneTileModel();
		if (model != null && drawTile)
		{
			Point p = t.getSceneLocation();
			uploadTileModel(model, p.getX() * Perspective.LOCAL_TILE_SIZE, p.getY() * Perspective.LOCAL_TILE_SIZE, t.getRenderLevel(), bucket);
		}

		WallObject wall = t.getWallObject();
		if (wall != null && renderCallbacks.drawObject(scene, wall))
		{
			pushRenderable(wall.getRenderable1(), 0, wall.getX(), wall.getZ(), wall.getY(), bucket);
			pushRenderable(wall.getRenderable2(), 0, wall.getX(), wall.getZ(), wall.getY(), bucket);
		}

		DecorativeObject deco = t.getDecorativeObject();
		if (deco != null && renderCallbacks.drawObject(scene, deco))
		{
			pushRenderable(deco.getRenderable(), 0, deco.getX() + deco.getXOffset(), deco.getZ(), deco.getY() + deco.getYOffset(), bucket);
			pushRenderable(deco.getRenderable2(), 0, deco.getX() + deco.getXOffset2(), deco.getZ(), deco.getY() + deco.getYOffset2(), bucket);
		}

		GroundObject ground = t.getGroundObject();
		if (ground != null && renderCallbacks.drawObject(scene, ground))
		{
			pushRenderable(ground.getRenderable(), 0, ground.getX(), ground.getZ(), ground.getY(), bucket);
		}

		for (GameObject go : t.getGameObjects())
		{
			if (go == null || !go.getSceneMinLocation().equals(t.getSceneLocation()))
			{
				continue;
			}
			if (!renderCallbacks.drawObject(scene, go))
			{
				continue;
			}
			int kind = foliageKind.applyAsInt(go.getId());
			pusher.flames = lit.test(go.getId());
			if (kind == 1 || kind == 2)
			{
				pushFoliage(go.getRenderable(), go.getModelOrientation(), go.getX(), go.getZ(), go.getY(), kind == 2 ? treeScale : 1f, bucket);
			}
			else
			{
				pushRenderable(go.getRenderable(), go.getModelOrientation(), go.getX(), go.getZ(), go.getY(), bucket);
			}
			pusher.flames = false;
		}

		Tile bridge = t.getBridge();
		if (bridge != null)
		{
			uploadTile(bridge, bucket);
		}
	}

	private void pushRenderable(Renderable r, int orientation, int x, int y, int z, Bucket bucket)
	{
		Model m;
		if (r instanceof Model)
		{
			m = (Model) r;
		}
		else if (r instanceof DynamicObject)
		{
			m = ((DynamicObject) r).getModelZbuf();
		}
		else
		{
			return;
		}
		if (m != null)
		{
			pusher.push(m, orientation, x, y, z, null, palette, bucket.opaque, bucket.translucent);
		}
	}

	// Foliage goes to its own group with a weight per vertex: the base stays put and the
	// canopy moves fully, so the wind bends the tree rather than sliding it.
	private void pushFoliage(Renderable r, int orientation, int x, int y, int z, float scale, Bucket bucket)
	{
		Model m = r instanceof Model ? (Model) r : r instanceof DynamicObject ? ((DynamicObject) r).getModelZbuf() : null;
		if (m == null)
		{
			return;
		}
		int first = bucket.sway.faces();
		int translucentBefore = bucket.translucent.faces();
		// Scaling about the object's own position keeps the trunk on the ground.
		float[] grow = scale == 1f ? null : new float[]{
			scale, 0f, 0f, x * (1f - scale),
			0f, scale, 0f, y * (1f - scale),
			0f, 0f, scale, z * (1f - scale)};
		pusher.push(m, orientation, x, y, z, grow, palette, bucket.sway, bucket.translucent);
		int added = bucket.sway.faces() - first;
		if (added == 0)
		{
			return;
		}
		// Translucent parts of foliage stay static; only the opaque faces sway.
		if (bucket.swayWeights.length < bucket.sway.faces() * 3)
		{
			bucket.swayWeights = java.util.Arrays.copyOf(bucket.swayWeights, Math.max(bucket.sway.faces() * 3, bucket.swayWeights.length * 2));
		}
		float height = Math.max(m.getModelHeight(), 1) * scale;
		float[] pos = bucket.sway.positions();
		for (int f = first; f < first + added; ++f)
		{
			for (int v = 0; v < 3; ++v)
			{
				float above = (y - pos[(f * 3 + v) * 3 + 1]) / height;
				float w = Math.max(0f, Math.min(1f, above));
				bucket.swayWeights[f * 3 + v] = w * w;
			}
		}
		assert bucket.translucent.faces() >= translucentBefore;
	}

	private int cornerColor(int hsl, int plane, int gridX, int gridY)
	{
		if (terrainLight == null)
		{
			return palette.hsl(hsl);
		}
		int size = terrainLight[0].length;
		int x = Math.max(0, Math.min(size - 1, gridX));
		int y = Math.max(0, Math.min(size - 1, gridY));
		return palette.hsl(Palette.undoTerrainShading(hsl, terrainLight[plane][x][y]));
	}

	// Tile model vertices sit on tile corners or edges; use the light of the nearest grid point.
	private int vertexColor(int hsl, int sceneX, int sceneZ, int plane)
	{
		int gx = Math.round(sceneX / (float) Perspective.LOCAL_TILE_SIZE) + offset;
		int gy = Math.round(sceneZ / (float) Perspective.LOCAL_TILE_SIZE) + offset;
		return cornerColor(hsl, plane, gx, gy);
	}

	private void uploadPaint(SceneTilePaint tile, int renderLevel, int sceneX, int sceneY, Bucket bucket)
	{
		GeometryBuffer out = bucket.opaque;
		int neColor = tile.getNeColor();
		if (neColor == HIDDEN_COLOR)
		{
			return;
		}

		int tx = sceneX + offset;
		int ty = sceneY + offset;
		int[][][] heights = scene.getTileHeights();
		float swH = heights[renderLevel][tx][ty];
		float seH = heights[renderLevel][tx + 1][ty];
		float neH = heights[renderLevel][tx + 1][ty + 1];
		float nwH = heights[renderLevel][tx][ty + 1];

		float lx = sceneX * Perspective.LOCAL_TILE_SIZE;
		float lz = sceneY * Perspective.LOCAL_TILE_SIZE;
		float hx = lx + Perspective.LOCAL_TILE_SIZE;
		float hz = lz + Perspective.LOCAL_TILE_SIZE;

		// Same vertex order as the GPU plugin so the winding matches its culling; UVs span the tile.
		if (tile.getTexture() != -1)
		{
			int texture = WaterTextures.encode(tile.getTexture());
			int rgba = palette.texture(tile.getTexture());
			if (WaterTextures.isWater(tile.getTexture()))
			{
				bucket.water.face(hx, neH, hz, lx, nwH, hz, hx, seH, lz, rgba, texture, 1f, 1f, 0f, 1f, 1f, 0f);
				bucket.water.face(lx, swH, lz, hx, seH, lz, lx, nwH, hz, rgba, texture, 0f, 0f, 1f, 0f, 0f, 1f);
				// The bed carries the shore's colours in, sunk by the shore distance (y grows
				// downwards); the shader darkens it by depth.
				int[][] depth = waterBed.depth[renderLevel];
				int sw = bedColor(renderLevel, tx, ty, cornerColor(tile.getSwColor(), renderLevel, tx, ty));
				int se = bedColor(renderLevel, tx + 1, ty, cornerColor(tile.getSeColor(), renderLevel, tx + 1, ty));
				int ne = bedColor(renderLevel, tx + 1, ty + 1, cornerColor(neColor, renderLevel, tx + 1, ty + 1));
				int nw = bedColor(renderLevel, tx, ty + 1, cornerColor(tile.getNwColor(), renderLevel, tx, ty + 1));
				out.face(hx, neH + depth[tx + 1][ty + 1], hz, lx, nwH + depth[tx][ty + 1], hz, hx, seH + depth[tx + 1][ty], lz, average(ne, nw, se));
				out.face(lx, swH + depth[tx][ty], lz, hx, seH + depth[tx + 1][ty], lz, lx, nwH + depth[tx][ty + 1], hz, average(sw, se, nw));
				return;
			}
			GeometryBuffer target = TextureCutouts.isCutout(tile.getTexture()) ? bucket.translucent : out;
			target.face(hx, neH, hz, lx, nwH, hz, hx, seH, lz, rgba, texture, 1f, 1f, 0f, 1f, 1f, 0f);
			target.face(lx, swH, lz, hx, seH, lz, lx, nwH, hz, rgba, texture, 0f, 0f, 1f, 0f, 0f, 1f);
			return;
		}
		int sw = cornerColor(tile.getSwColor(), renderLevel, tx, ty);
		int se = cornerColor(tile.getSeColor(), renderLevel, tx + 1, ty);
		int ne = cornerColor(neColor, renderLevel, tx + 1, ty + 1);
		int nw = cornerColor(tile.getNwColor(), renderLevel, tx, ty + 1);
		terrainFace(out, hx, neH, hz, lx, nwH, hz, hx, seH, lz, ne, nw, se);
		terrainFace(out, lx, swH, lz, hx, seH, lz, lx, nwH, hz, sw, se, nw);
	}

	private void uploadTileModel(SceneTileModel model, int tileX, int tileZ, int renderLevel, Bucket bucket)
	{
		GeometryBuffer out = bucket.opaque;
		int[] fx = model.getFaceX();
		int[] fy = model.getFaceY();
		int[] fz = model.getFaceZ();
		int[] vx = model.getVertexX();
		int[] vy = model.getVertexY();
		int[] vz = model.getVertexZ();
		int[] ca = model.getTriangleColorA();
		int[] cb = model.getTriangleColorB();
		int[] cc = model.getTriangleColorC();
		int[] textures = model.getTriangleTextureId();

		out.ensure(fx.length);
		for (int i = 0; i < fx.length; ++i)
		{
			if (ca[i] == HIDDEN_COLOR)
			{
				continue;
			}
			int a = fx[i], b = fy[i], c = fz[i];
			if (textures != null && textures[i] != -1)
			{
				float scale = 1f / Perspective.LOCAL_TILE_SIZE;
				if (WaterTextures.isWater(textures[i]))
				{
					bucket.water.face(vx[a], vy[a], vz[a], vx[b], vy[b], vz[b], vx[c], vy[c], vz[c],
						palette.texture(textures[i]), WaterTextures.encode(textures[i]),
						(vx[a] - tileX) * scale, (vz[a] - tileZ) * scale,
						(vx[b] - tileX) * scale, (vz[b] - tileZ) * scale,
						(vx[c] - tileX) * scale, (vz[c] - tileZ) * scale);
					int bedColor = average(bedVertexColor(ca[i], vx[a], vz[a], renderLevel), bedVertexColor(cb[i], vx[b], vz[b], renderLevel), bedVertexColor(cc[i], vx[c], vz[c], renderLevel));
					out.face(vx[a], vy[a] + bedDepth(vx[a], vz[a], renderLevel), vz[a],
						vx[b], vy[b] + bedDepth(vx[b], vz[b], renderLevel), vz[b],
						vx[c], vy[c] + bedDepth(vx[c], vz[c], renderLevel), vz[c], bedColor);
					continue;
				}
				GeometryBuffer target = TextureCutouts.isCutout(textures[i]) ? bucket.translucent : out;
				target.face(vx[a], vy[a], vz[a], vx[b], vy[b], vz[b], vx[c], vy[c], vz[c],
					palette.texture(textures[i]), WaterTextures.encode(textures[i]),
					(vx[a] - tileX) * scale, (vz[a] - tileZ) * scale,
					(vx[b] - tileX) * scale, (vz[b] - tileZ) * scale,
					(vx[c] - tileX) * scale, (vz[c] - tileZ) * scale);
				continue;
			}
			terrainFace(out, vx[a], vy[a], vz[a], vx[b], vy[b], vz[b], vx[c], vy[c], vz[c],
				vertexColor(ca[i], vx[a], vz[a], renderLevel), vertexColor(cb[i], vx[b], vz[b], renderLevel), vertexColor(cc[i], vx[c], vz[c], renderLevel));
		}
	}

	private int bedDepth(int sceneX, int sceneZ, int plane)
	{
		int[][] depth = waterBed.depth[plane];
		int gx = Math.max(0, Math.min(depth.length - 1, Math.round(sceneX / (float) Perspective.LOCAL_TILE_SIZE) + offset));
		int gy = Math.max(0, Math.min(depth.length - 1, Math.round(sceneZ / (float) Perspective.LOCAL_TILE_SIZE) + offset));
		return depth[gx][gy];
	}

	private int bedVertexColor(int hsl, int sceneX, int sceneZ, int plane)
	{
		int gx = Math.round(sceneX / (float) Perspective.LOCAL_TILE_SIZE) + offset;
		int gy = Math.round(sceneZ / (float) Perspective.LOCAL_TILE_SIZE) + offset;
		return bedColor(plane, gx, gy, vertexColor(hsl, sceneX, sceneZ, plane));
	}

	private static int average(int a, int b, int c)
	{
		int r = ((a & 0xff) + (b & 0xff) + (c & 0xff)) / 3;
		int g = ((a >> 8 & 0xff) + (b >> 8 & 0xff) + (c >> 8 & 0xff)) / 3;
		int bl = ((a >> 16 & 0xff) + (b >> 16 & 0xff) + (c >> 16 & 0xff)) / 3;
		return 0xff000000 | bl << 16 | g << 8 | r;
	}
}
