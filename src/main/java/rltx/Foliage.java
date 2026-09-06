package rltx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.callback.ClientThread;
import rltx.scene.GeometryBuffer;
import rltx.scene.StaticScene;
import rltx.scene.lights.LightDefinition;
import rltx.sky.WeatherState;
import rltx.vk.FrameParams;
import rltx.vk.RtRenderer;

/**
 * What the scene's objects are by name, and the foliage near the camera drawn each frame as
 * swayed copies through the dynamic path, its static group skipped, bent by the wind and by
 * whoever walks through it.
 */
final class Foliage
{
	private static final String[] TREE_WORDS = {"tree", "oak", "willow", "yew", "maple", "palm", "mahogany", "teak", "redwood"};
	private static final String[] FOLIAGE_WORDS = {"bush", "shrub", "fern", "leaves", "plant", "flower", "grass", "reed", "vine", "hedge"};
	private static final String[] GRAVE_WORDS = {"grave", "tomb", "coffin", "headstone", "crypt", "sarcophag", "mausoleum"};
	private static final Pattern FIRE_WORDS = Pattern.compile("\\b(fire|campfire|bonfire|brazier|forge|furnace|range|pyre|hearth|fireplace|stove|oven)\\b", Pattern.CASE_INSENSITIVE);
	private static final float SWAY_RANGE = 24 * Perspective.LOCAL_TILE_SIZE;
	private static final int SWAY_FACE_BUDGET = 150_000;
	// Where everyone is standing, for the plants they brush: x, ground height and z each.
	private static final float BRUSH_RADIUS = 80f;
	private static final int MAX_WALKERS = 96;

	private final Client client;
	private final ClientThread clientThread;
	private final RltxConfig config;
	private final LocalLights lights;
	private final FrameParams frame;
	/** 0 rigid, 1 foliage that sways, 2 a tree that sways and scales, 3 a grave that gathers mist, 4 a chimney or fire that smokes. */
	private final Map<Integer, Integer> kinds = new ConcurrentHashMap<>();
	private float[] swayScratch = new float[0];
	private final float[] walkerPos = new float[MAX_WALKERS * 3];
	private final float[] nearPos = new float[MAX_WALKERS * 3];

	Foliage(Client client, ClientThread clientThread, RltxConfig config, LocalLights lights, FrameParams frame)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.lights = lights;
		this.frame = frame;
	}

	int kind(int objectId)
	{
		return kinds.getOrDefault(objectId, 0);
	}

	boolean isMisty(int objectId)
	{
		return kind(objectId) == 3;
	}

	// Whether 117 HD describes the object's light as a fire of some kind, so smoke rises from it.
	private boolean smokes(int objectId)
	{
		List<LightDefinition> defs = lights.library().byObject.get(objectId);
		if (defs == null)
		{
			return false;
		}
		for (LightDefinition def : defs)
		{
			if (def.description != null && FIRE_WORDS.matcher(def.description).find())
			{
				return true;
			}
		}
		return false;
	}

	// Object names live in the client's cache, which the scene loader thread must not touch, so
	// unknown ids are resolved on the client thread first, as the GPU plugin does for its uploads.
	void classify(Set<Integer> ids)
	{
		List<Integer> unknown = new ArrayList<>();
		for (Integer id : ids)
		{
			if (!kinds.containsKey(id))
			{
				unknown.add(id);
			}
		}
		if (unknown.isEmpty())
		{
			return;
		}
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (Integer id : unknown)
			{
				ObjectComposition def = client.getObjectDefinition(id);
				String name = def == null || def.getName() == null ? "" : def.getName().toLowerCase(Locale.ROOT);
				int kind = 0;
				if (!name.contains("stump"))
				{
					for (String word : TREE_WORDS)
					{
						if (name.contains(word))
						{
							kind = 2;
							break;
						}
					}
					for (int i = 0; kind == 0 && i < FOLIAGE_WORDS.length; ++i)
					{
						if (name.contains(FOLIAGE_WORDS[i]))
						{
							kind = 1;
						}
					}
					for (int i = 0; kind == 0 && i < GRAVE_WORDS.length; ++i)
					{
						if (name.contains(GRAVE_WORDS[i]))
						{
							kind = 3;
						}
					}
					if (kind == 0 && (name.contains("chimney") || smokes(id)))
					{
						kind = 4;
					}
				}
				kinds.put(id, kind);
			}
			latch.countDown();
		});
		try
		{
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	// The wind is a slow gust field with the weather's wind on top.
	void push(LoadedScene top, GeometryBuffer dynamic, RtRenderer renderer, WeatherState weather)
	{
		if (!config.foliageWind() || top == null)
		{
			renderer.setSwayedZones(WorldView.TOPLEVEL, null);
			return;
		}
		StaticScene built = top.built;
		if (top.swayed == null || top.swayed.length != built.zones.length)
		{
			top.swayed = new boolean[built.zones.length];
		}
		float t = frame.timeSeconds;
		float amplitude = (4f + 10f * weather.wind) * config.foliageWindStrength() / 100f;
		double to = Math.toRadians(weather.windFromDegrees + 180.0);
		float dirX = (float) Math.sin(to);
		float dirZ = (float) Math.cos(to);
		int offsetTiles = (built.zonesX * 8 - Constants.SCENE_SIZE) / 2;
		int budget = SWAY_FACE_BUDGET;
		int walkers = config.footprints() ? collectWalkers() : 0;
		for (int i = 0; i < built.zones.length; ++i)
		{
			StaticScene.Zone zone = built.zones[i];
			top.swayed[i] = false;
			if (zone == null || zone.sway.faces() == 0 || budget < zone.sway.faces())
			{
				continue;
			}
			float centreX = ((i / built.zonesZ) * 8 - offsetTiles + 4) * Perspective.LOCAL_TILE_SIZE;
			float centreZ = ((i % built.zonesZ) * 8 - offsetTiles + 4) * Perspective.LOCAL_TILE_SIZE;
			float dx = centreX - frame.cameraX;
			float dz = centreZ - frame.cameraZ;
			if (dx * dx + dz * dz > SWAY_RANGE * SWAY_RANGE)
			{
				continue;
			}
			top.swayed[i] = true;
			budget -= zone.sway.faces();
			int faces = zone.sway.faces();
			float[] pos = zone.sway.positions();
			float[] weights = zone.swayWeights;
			int[] colors = zone.sway.colors();
			int[] textures = zone.sway.textures();
			float[] uvs = zone.sway.uvs();
			if (swayScratch.length < faces * 9)
			{
				swayScratch = new float[faces * 9];
			}
			// Only walkers in or beside this zone can be brushing its plants.
			int near = 0;
			for (int a = 0; a < walkers; ++a)
			{
				if (Math.abs(walkerPos[a * 3] - centreX) < 4.5f * Perspective.LOCAL_TILE_SIZE && Math.abs(walkerPos[a * 3 + 2] - centreZ) < 4.5f * Perspective.LOCAL_TILE_SIZE)
				{
					System.arraycopy(walkerPos, a * 3, nearPos, near * 3, 3);
					++near;
				}
			}
			int start = dynamic.faces();
			for (int f = 0; f < faces; ++f)
			{
				int o = f * 9;
				float px = pos[o];
				float pz = pos[o + 2];
				// A tall tree is stiffer and slower than a shrub: its crown moves less, in longer gusts.
				float height = (float) Math.floor(weights[f * 3]);
				float stiff = Math.min(1f, 260f / Math.max(height, 1f));
				float pace = 0.55f + 0.45f * stiff;
				float gust = amplitude * stiff * ((float) Math.sin(t * 1.1 * pace + px * 0.006 + pz * 0.004) + 0.5f * (float) Math.sin(t * 2.3 * pace + pz * 0.011));
				float ox = gust * (0.6f * dirX + 0.4f * (float) Math.sin(t * 0.7 * pace + px * 0.01));
				float oz = gust * (0.6f * dirZ + 0.4f * (float) Math.cos(t * 0.9 * pace + pz * 0.008));
				// Low plants lean away from anyone standing in them; trees are above the reach.
				for (int a = 0; a < near; ++a)
				{
					float ax = px - nearPos[a * 3];
					float az = pz - nearPos[a * 3 + 2];
					float d2 = ax * ax + az * az;
					if (d2 < BRUSH_RADIUS * BRUSH_RADIUS && d2 > 1f && nearPos[a * 3 + 1] - pos[o + 1] < 90f)
					{
						float d = (float) Math.sqrt(d2);
						float push = (1f - d / BRUSH_RADIUS) * 36f / d;
						ox += ax * push;
						oz += az * push;
					}
				}
				float rustle = 0.3f * amplitude;
				for (int v = 0; v < 3; ++v)
				{
					float w = weights[f * 3 + v] - height;
					float py = pos[o + v * 3 + 1];
					// Leaves shiver on their own on top of the whole plant's sway.
					float rx = rustle * (float) Math.sin(t * 3.7 + pos[o + v * 3] * 0.05 + py * 0.03);
					float rz = rustle * (float) Math.cos(t * 4.1 + pos[o + v * 3 + 2] * 0.05 - py * 0.04);
					swayScratch[o + v * 3] = pos[o + v * 3] + (ox + rx) * w;
					swayScratch[o + v * 3 + 1] = py;
					swayScratch[o + v * 3 + 2] = pos[o + v * 3 + 2] + (oz + rz) * w;
				}
				int uo = f * 6;
				dynamic.face(swayScratch[o], swayScratch[o + 1], swayScratch[o + 2], swayScratch[o + 3], swayScratch[o + 4], swayScratch[o + 5],
					swayScratch[o + 6], swayScratch[o + 7], swayScratch[o + 8], colors[f], textures[f],
					uvs[uo], uvs[uo + 1], uvs[uo + 2], uvs[uo + 3], uvs[uo + 4], uvs[uo + 5]);
			}
			dynamic.setPreviousPositions(start, swayScratch, faces);
		}
		renderer.setSwayedZones(WorldView.TOPLEVEL, top.swayed);
	}

	private int collectWalkers()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return 0;
		}
		int plane = client.getPlane();
		int n = 0;
		for (Player player : wv.players())
		{
			n = walker(player, plane, n);
		}
		for (NPC npc : wv.npcs())
		{
			n = walker(npc, plane, n);
		}
		return n;
	}

	private int walker(Actor actor, int plane, int n)
	{
		LocalPoint lp = actor.getLocalLocation();
		if (n >= MAX_WALKERS || lp == null || actor.getWorldLocation().getPlane() != plane)
		{
			return n;
		}
		walkerPos[n * 3] = lp.getX();
		walkerPos[n * 3 + 1] = Perspective.getTileHeight(client, lp, plane);
		walkerPos[n * 3 + 2] = lp.getY();
		return n + 1;
	}
}
