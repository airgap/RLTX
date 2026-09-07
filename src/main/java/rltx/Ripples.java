package rltx;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import rltx.vk.FrameParams;
import rltx.vk.RtRenderer;

/**
 * Feeds the water ripple simulation: where its window of cells sits this frame, how far to step
 * it, how much rain falls into it, and who is wading through it with what force.
 */
final class Ripples
{
	static final int CELLS = 512;
	static final float CELL_SIZE = 8f;
	private static final int MAX_WALKERS = 64;
	/** Wave speed in world units a second; about two tiles. */
	private static final float WAVE_SPEED = 250f;
	private static final float RAIN_RATE = 0.0004f;

	private final Client client;
	private final RltxConfig config;
	private final FrameParams frame;
	private final float[] packed = new float[(3 + MAX_WALKERS) * 4];
	private final Map<Actor, float[]> lastPosition = new HashMap<>();
	private int prevBaseX = Integer.MIN_VALUE;
	private int prevBaseZ = Integer.MIN_VALUE;

	Ripples(Client client, RltxConfig config, FrameParams frame)
	{
		this.client = client;
		this.config = config;
		this.frame = frame;
	}

	void fill(RtRenderer renderer, LoadedScene top, float dt)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (!config.waterRipples() || top == null || wv == null)
		{
			frame.ripples = false;
			lastPosition.clear();
			prevBaseX = Integer.MIN_VALUE;
			return;
		}
		int baseX = (int) Math.floor(frame.cameraX / CELL_SIZE) - CELLS / 2;
		int baseZ = (int) Math.floor(frame.cameraZ / CELL_SIZE) - CELLS / 2;
		// A window far from the last one makes every cell fresh, which also empties the field
		// the first time and after the setting comes back on.
		packed[0] = baseX;
		packed[1] = baseZ;
		packed[2] = prevBaseX == Integer.MIN_VALUE ? baseX + 100_000 : prevBaseX;
		packed[3] = prevBaseZ == Integer.MIN_VALUE ? baseZ + 100_000 : prevBaseZ;
		prevBaseX = baseX;
		prevBaseZ = baseZ;
		// Even steps leave the result in the first image; more of them at low frame rates keep the
		// wave term within the stable range at the same wave speed.
		float step = Math.max(dt, 1f / 240f);
		int steps = step <= 1f / 45f ? 2 : 4;
		float substep = step / steps;
		float c = WAVE_SPEED * substep / CELL_SIZE;
		packed[4] = CELL_SIZE;
		packed[5] = Math.min(c * c, 0.45f);
		packed[6] = frame.rain * RAIN_RATE;
		int plane = client.getPlane();
		int count = 0;
		for (Player player : wv.players())
		{
			count = walker(player, plane, top, dt, count);
		}
		for (NPC npc : wv.npcs())
		{
			count = walker(npc, plane, top, dt, count);
		}
		packed[7] = count;
		renderer.setRipples(packed, (3 + count) * 4);
		frame.ripples = true;
		frame.rippleSteps = steps;
	}

	// Someone standing in water disturbs it a little, and wading a lot, so the wake follows their pace.
	private int walker(Actor actor, int plane, LoadedScene top, float dt, int count)
	{
		LocalPoint lp = actor.getLocalLocation();
		if (lp == null || count >= MAX_WALKERS || actor.getWorldLocation().getPlane() != plane)
		{
			return count;
		}
		float[] last = lastPosition.get(actor);
		float speed = 0f;
		if (last != null && dt > 0f)
		{
			float dx = lp.getX() - last[0];
			float dz = lp.getY() - last[1];
			speed = (float) Math.sqrt(dx * dx + dz * dz) / dt;
		}
		if (last == null)
		{
			last = new float[2];
			lastPosition.put(actor, last);
		}
		last[0] = lp.getX();
		last[1] = lp.getY();
		if (!top.waterBed.isWater(plane, lp.getSceneX(), lp.getSceneY()))
		{
			return count;
		}
		int o = (3 + count) * 4;
		packed[o] = lp.getX();
		packed[o + 1] = lp.getY();
		packed[o + 2] = 0.25f + 1.6f * Math.min(speed / WAVE_SPEED, 1f);
		packed[o + 3] = 0f;
		return count + 1;
	}

	/** Actors gone from the world drop out of the pace tracking; once a game tick. */
	void retainPresent(WorldView view)
	{
		if (lastPosition.isEmpty())
		{
			return;
		}
		java.util.Set<Actor> present = new java.util.HashSet<>();
		if (view != null)
		{
			for (Player player : view.players())
			{
				present.add(player);
			}
			for (NPC npc : view.npcs())
			{
				present.add(npc);
			}
		}
		lastPosition.keySet().retainAll(present);
	}
}
