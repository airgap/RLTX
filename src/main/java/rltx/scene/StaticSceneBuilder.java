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
import net.runelite.client.callback.RenderCallbackManager;

/**
 * Walks the extended scene and buckets every static triangle by
 * (render level, roof id), following the grouping the GPU plugin's
 * SceneUploader uses so the same per-frame visibility rules apply.
 */
public final class StaticSceneBuilder
{
	private static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	private static final int HIDDEN_COLOR = 12345678;

	private final Scene scene;
	private final RenderCallbackManager renderCallbacks;
	private final Palette palette;
	private final ModelPusher pusher = new ModelPusher();
	private final Map<Long, Bucket> groups = new LinkedHashMap<>();

	private static final class Bucket
	{
		final GeometryBuffer opaque = new GeometryBuffer(1024);
		final GeometryBuffer translucent = new GeometryBuffer(64);
	}

	private StaticSceneBuilder(Scene scene, RenderCallbackManager renderCallbacks, Palette palette)
	{
		this.scene = scene;
		this.renderCallbacks = renderCallbacks;
		this.palette = palette;
	}

	public static StaticScene build(Scene scene, RenderCallbackManager renderCallbacks, Palette palette)
	{
		StaticSceneBuilder b = new StaticSceneBuilder(scene, renderCallbacks, palette);
		b.walk();
		return b.finish();
	}

	private void walk()
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		byte[][][] settings = scene.getExtendedTileSettings();
		int[][][] roofs = scene.getRoofs();

		for (int level = 0; level <= 3; ++level)
		{
			for (int msx = 0; msx < Constants.EXTENDED_SCENE_SIZE; ++msx)
			{
				for (int msz = 0; msz < Constants.EXTENDED_SCENE_SIZE; ++msz)
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

					uploadTile(t, group(groupLevel, roofId));
				}
			}
		}
	}

	private Bucket group(int level, int roofId)
	{
		long key = (long) level << 32 | (roofId & 0xffffffffL);
		return groups.computeIfAbsent(key, k -> new Bucket());
	}

	private StaticScene finish()
	{
		int total = 0;
		int count = 0;
		for (Bucket b : groups.values())
		{
			total += b.opaque.faces() + b.translucent.faces();
			count += (b.opaque.faces() > 0 ? 1 : 0) + (b.translucent.faces() > 0 ? 1 : 0);
		}

		GeometryBuffer all = new GeometryBuffer(Math.max(total, 1));
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
		return new StaticScene(all, level, roof, base, faces, translucent);
	}

	private void uploadTile(Tile t, Bucket bucket)
	{
		GeometryBuffer out = bucket.opaque;
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
			uploadTileModel(model, p.getX() * Perspective.LOCAL_TILE_SIZE, p.getY() * Perspective.LOCAL_TILE_SIZE, bucket);
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
			pusher.push(m, orientation, x, y, z, palette, bucket.opaque, bucket.translucent);
		}
	}

	private void uploadPaint(SceneTilePaint tile, int renderLevel, int sceneX, int sceneY, Bucket bucket)
	{
		GeometryBuffer out = bucket.opaque;
		int neColor = tile.getNeColor();
		if (neColor == HIDDEN_COLOR)
		{
			return;
		}

		int tx = sceneX + SCENE_OFFSET;
		int ty = sceneY + SCENE_OFFSET;
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
		int sw = palette.hsl(tile.getSwColor());
		int se = palette.hsl(tile.getSeColor());
		int ne = palette.hsl(neColor);
		int nw = palette.hsl(tile.getNwColor());
		out.face(hx, neH, hz, lx, nwH, hz, hx, seH, lz, average(ne, nw, se));
		out.face(lx, swH, lz, hx, seH, lz, lx, nwH, hz, average(sw, se, nw));
	}

	private void uploadTileModel(SceneTileModel model, int tileX, int tileZ, Bucket bucket)
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
			int rgba = average(palette.hsl(ca[i]), palette.hsl(cb[i]), palette.hsl(cc[i]));
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
