package rltx.scene.lights;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;

/**
 * Lights placed in a loaded scene: the fixed ones inside its bounds and those attached to its
 * objects, plus per frame the ones following NPCs. Packs the nearest for the GPU with 117 HD's
 * flicker and pulse animation applied.
 */
public final class SceneLights
{
	public static final int FLOATS_PER_LIGHT = 8;
	private static final float MAX_STRENGTH = 60f;

	public static final class Placed
	{
		final LightDefinition def;
		final float x;
		final float y;
		final float z;
		final float phase;

		Placed(LightDefinition def, float x, float y, float z, float phase)
		{
			this.def = def;
			this.x = x;
			this.y = y;
			this.z = z;
			this.phase = phase;
		}
	}

	private final List<Placed> scene = new ArrayList<>();
	private final List<Placed> frame = new ArrayList<>();
	private final float[] packed;
	private final Random random = new Random(7);
	private final float[] placement = new float[3];

	public SceneLights(int maxLights)
	{
		packed = new float[maxLights * FLOATS_PER_LIGHT];
	}

	/** Gathers the fixed and object-attached lights of a freshly loaded top-level scene. */
	public void collect(Scene scene, LightLibrary library)
	{
		this.scene.clear();
		int[][][] heights = scene.getTileHeights();
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();
		int offset = (scene.getExtendedTiles()[0].length - Constants.SCENE_SIZE) / 2;
		for (LightDefinition def : library.fixed)
		{
			int tx = def.worldX - baseX;
			int ty = def.worldY - baseY;
			if (tx < -offset || ty < -offset || tx >= Constants.SCENE_SIZE + offset || ty >= Constants.SCENE_SIZE + offset || def.plane < 0 || def.plane > 3)
			{
				continue;
			}
			float x = tx * Perspective.LOCAL_TILE_SIZE + 64f;
			float z = ty * Perspective.LOCAL_TILE_SIZE + 64f;
			float ground = heights[def.plane][tx + offset][ty + offset];
			this.scene.add(new Placed(def, x, ground - 1f - def.height, z, random.nextFloat()));
		}
		if (library.byObject.isEmpty())
		{
			return;
		}
		Tile[][][] tiles = scene.getExtendedTiles();
		for (int level = 0; level < tiles.length; ++level)
		{
			for (Tile[] column : tiles[level])
			{
				for (Tile tile : column)
				{
					if (tile != null)
					{
						collectObjects(tile, library, heights, offset);
					}
				}
			}
		}
	}

	private void collectObjects(Tile tile, LightLibrary library, int[][][] heights, int offset)
	{
		for (GameObject go : tile.getGameObjects())
		{
			if (go != null && go.getSceneMinLocation().equals(tile.getSceneLocation()))
			{
				placeObject(library.byObject.get(go.getId()), go.getX(), go.getZ(), go.getY(), go.getModelOrientation(), go.sizeX(), go.sizeY());
			}
		}
		WallObject wall = tile.getWallObject();
		if (wall != null)
		{
			placeObject(library.byObject.get(wall.getId()), wall.getX(), wall.getZ(), wall.getY(), 0, 1, 1);
		}
		DecorativeObject deco = tile.getDecorativeObject();
		if (deco != null)
		{
			placeObject(library.byObject.get(deco.getId()), deco.getX() + deco.getXOffset(), deco.getZ(), deco.getY() + deco.getYOffset(), 0, 1, 1);
		}
		GroundObject ground = tile.getGroundObject();
		if (ground != null)
		{
			placeObject(library.byObject.get(ground.getId()), ground.getX(), ground.getZ(), ground.getY(), 0, 1, 1);
		}
		if (tile.getBridge() != null)
		{
			collectObjects(tile.getBridge(), library, heights, offset);
		}
	}

	// Object coordinates arrive as scene x, height and scene y (which is our z).
	private void placeObject(List<LightDefinition> defs, int x, int height, int z, int orientation, int sizeX, int sizeY)
	{
		if (defs == null)
		{
			return;
		}
		for (LightDefinition def : defs)
		{
			def.placement(orientation, sizeX, sizeY, placement);
			scene.add(new Placed(def, x + placement[0], height - 1f - def.height + placement[1], z + placement[2], random.nextFloat()));
		}
	}

	/**
	 * Packs this frame's lights, nearest to the camera first, up to the buffer's capacity.
	 *
	 * @param npcs      NPCs currently in the world view, for attached lights
	 * @param npcHeight ground height under an NPC's position
	 * @return how many lights were packed
	 */
	public int pack(Iterable<? extends NPC> npcs, LightLibrary library, HeightLookup npcHeight, float camX, float camY, float camZ, float seconds)
	{
		frame.clear();
		frame.addAll(scene);
		for (NPC npc : npcs)
		{
			List<LightDefinition> defs = npc == null ? null : library.byNpc.get(npc.getId());
			if (defs == null)
			{
				continue;
			}
			LocalPoint lp = npc.getLocalLocation();
			if (lp == null)
			{
				continue;
			}
			float ground = npcHeight.at(lp, npc.getWorldLocation().getPlane());
			for (LightDefinition def : defs)
			{
				def.placement(npc.getCurrentOrientation(), 1, 1, placement);
				frame.add(new Placed(def, lp.getX() + placement[0], ground - 1f - def.height + placement[1], lp.getY() + placement[2], 0.37f));
			}
		}
		int capacity = packed.length / FLOATS_PER_LIGHT;
		if (frame.size() > capacity)
		{
			frame.sort((a, b) -> Float.compare(distance2(a, camX, camY, camZ), distance2(b, camX, camY, camZ)));
		}
		int count = Math.min(frame.size(), capacity);
		Arrays.fill(packed, 0, count * FLOATS_PER_LIGHT, 0f);
		for (int i = 0; i < count; ++i)
		{
			Placed p = frame.get(i);
			float strength = Math.min(p.def.strength, MAX_STRENGTH);
			float radius = p.def.radius;
			switch (p.def.type)
			{
				case FLICKER:
				{
					// 117 HD's flicker: a sum of cosine harmonics over a minute-long cycle.
					float t = (float) (2.0 * Math.PI * ((seconds % 60f) / 60f + p.phase));
					float flicker = (float) ((Math.pow(Math.cos(11 * t), 3) + Math.pow(Math.cos(17 * t), 6) + Math.pow(Math.cos(23 * t), 2)
						+ Math.pow(Math.cos(31 * t), 6) + Math.pow(Math.cos(71 * t), 4) + Math.pow(Math.cos(151 * t), 6) / 2) / 4.335);
					float span = p.def.range / 100f;
					strength *= (1f - span) + 2f * span * flicker;
					radius *= 1.5f;
					break;
				}
				case PULSE:
				{
					float duration = p.def.duration > 0f ? p.def.duration / 1000f : 1f;
					float cycle = ((seconds / duration) + p.phase) % 1f;
					float output = 1f - 2f * Math.abs(cycle - 0.5f);
					float multiplier = 1f + (2f * output - 1f) * p.def.range / 100f;
					strength *= multiplier;
					radius *= multiplier;
					break;
				}
				default:
					break;
			}
			float[] rgb = p.def.rgb();
			int o = i * FLOATS_PER_LIGHT;
			packed[o] = p.x;
			packed[o + 1] = p.y;
			packed[o + 2] = p.z;
			packed[o + 3] = radius;
			packed[o + 4] = rgb[0] * strength;
			packed[o + 5] = rgb[1] * strength;
			packed[o + 6] = rgb[2] * strength;
		}
		return count;
	}

	public float[] packed()
	{
		return packed;
	}

	private static float distance2(Placed p, float x, float y, float z)
	{
		float dx = p.x - x;
		float dy = p.y - y;
		float dz = p.z - z;
		return dx * dx + dy * dy + dz * dz;
	}

	public interface HeightLookup
	{
		float at(LocalPoint point, int plane);
	}
}
