package rltx.scene;

import java.util.LinkedHashMap;
import java.util.Map;
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

	private final Scene scene;
	private final RenderCallbackManager renderCallbacks;
	private final Palette palette;
	private final int offset;
	private final int[][][] terrainLight;
	private final ModelPusher pusher = new ModelPusher();

	private static final class Bucket
	{
		final GeometryBuffer opaque = new GeometryBuffer(256);
		final GeometryBuffer translucent = new GeometryBuffer(32);
	}

	private StaticSceneBuilder(Scene scene, RenderCallbackManager renderCallbacks, Palette palette, int[][][] terrainLight)
	{
		this.scene = scene;
		this.renderCallbacks = renderCallbacks;
		this.palette = palette;
		this.offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? TOPLEVEL_OFFSET : 0;
		this.terrainLight = terrainLight;
	}

	/** Number of zones along each axis of the scene's extended tile grid. */
	public static int zoneCount(Scene scene)
	{
		return scene.getExtendedTiles()[0].length >> 3;
	}

	/** Builds every zone of the scene. */
	public static StaticScene build(Scene scene, RenderCallbackManager renderCallbacks, Palette palette)
	{
		int[][][] light = terrainLight(scene, palette);
		int zones = zoneCount(scene);
		StaticScene.Zone[] out = new StaticScene.Zone[zones * zones];
		StaticSceneBuilder builder = new StaticSceneBuilder(scene, renderCallbacks, palette, light);
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
	public static StaticScene.Zone buildZone(Scene scene, int zx, int zz, RenderCallbackManager renderCallbacks, Palette palette, int[][][] terrainLight)
	{
		return new StaticSceneBuilder(scene, renderCallbacks, palette, terrainLight).zone(zx, zz);
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
		for (Bucket b : groups.values())
		{
			total += b.opaque.faces() + b.translucent.faces();
			count += (b.opaque.faces() > 0 ? 1 : 0) + (b.translucent.faces() > 0 ? 1 : 0);
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
		int i = 0;
		for (Map.Entry<Long, Bucket> e : groups.entrySet())
		{
			Bucket b = e.getValue();
			for (int pass = 0; pass < 2; ++pass)
			{
				GeometryBuffer g = pass == 0 ? b.opaque : b.translucent;
				if (g.faces() == 0)
				{
					continue;
				}
				level[i] = (int) (e.getKey() >> 32);
				roof[i] = (int) (e.getKey() & 0xffffffffL);
				base[i] = all.faces();
				faces[i] = g.faces();
				translucent[i] = pass == 1;
				all.append(g);
				++i;
			}
		}
		return new StaticScene.Zone(zx, zz, all, level, roof, base, faces, translucent);
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
			pushRenderable(go.getRenderable(), go.getModelOrientation(), go.getX(), go.getZ(), go.getY(), bucket);
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
			GeometryBuffer target = TextureCutouts.isCutout(tile.getTexture()) ? bucket.translucent : out;
			target.face(hx, neH, hz, lx, nwH, hz, hx, seH, lz, rgba, texture, 1f, 1f, 0f, 1f, 1f, 0f);
			target.face(lx, swH, lz, hx, seH, lz, lx, nwH, hz, rgba, texture, 0f, 0f, 1f, 0f, 0f, 1f);
			return;
		}
		int sw = cornerColor(tile.getSwColor(), renderLevel, tx, ty);
		int se = cornerColor(tile.getSeColor(), renderLevel, tx + 1, ty);
		int ne = cornerColor(neColor, renderLevel, tx + 1, ty + 1);
		int nw = cornerColor(tile.getNwColor(), renderLevel, tx, ty + 1);
		out.face(hx, neH, hz, lx, nwH, hz, hx, seH, lz, average(ne, nw, se));
		out.face(lx, swH, lz, hx, seH, lz, lx, nwH, hz, average(sw, se, nw));
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
				GeometryBuffer target = TextureCutouts.isCutout(textures[i]) ? bucket.translucent : out;
				target.face(vx[a], vy[a], vz[a], vx[b], vy[b], vz[b], vx[c], vy[c], vz[c],
					palette.texture(textures[i]), WaterTextures.encode(textures[i]),
					(vx[a] - tileX) * scale, (vz[a] - tileZ) * scale,
					(vx[b] - tileX) * scale, (vz[b] - tileZ) * scale,
					(vx[c] - tileX) * scale, (vz[c] - tileZ) * scale);
				continue;
			}
			int rgba = average(vertexColor(ca[i], vx[a], vz[a], renderLevel), vertexColor(cb[i], vx[b], vz[b], renderLevel), vertexColor(cc[i], vx[c], vz[c], renderLevel));
			out.face(vx[a], vy[a], vz[a], vx[b], vy[b], vz[b], vx[c], vy[c], vz[c], rgba);
		}
	}

	private static int average(int a, int b, int c)
	{
		int r = ((a & 0xff) + (b & 0xff) + (c & 0xff)) / 3;
		int g = ((a >> 8 & 0xff) + (b >> 8 & 0xff) + (c >> 8 & 0xff)) / 3;
		int bl = ((a >> 16 & 0xff) + (b >> 16 & 0xff) + (c >> 16 & 0xff)) / 3;
		return 0xff000000 | bl << 16 | g << 8 | r;
	}
}
