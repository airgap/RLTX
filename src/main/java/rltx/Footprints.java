package rltx;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import rltx.vk.FrameParams;
import rltx.vk.RtRenderer;

/** Footprints: the last steps of everyone in view, alternating feet, kept in a ring. */
final class Footprints
{
	private final Client client;
	private final RltxConfig config;
	private final FrameParams frame;
	private final float[] packed = new float[RtRenderer.MAX_PRINTS * 8];
	private int count, next;
	private final Map<Actor, float[]> lastStep = new HashMap<>();

	Footprints(Client client, RltxConfig config, FrameParams frame)
	{
		this.client = client;
		this.config = config;
		this.frame = frame;
	}

	/** Actors gone from the world drop out of the tracking; once a game tick. */
	void retainPresent(WorldView view)
	{
		if (lastStep.isEmpty())
		{
			return;
		}
		Set<Actor> present = new HashSet<>();
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
		lastStep.keySet().retainAll(present);
	}

	void track(RtRenderer renderer, Cells cells)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (!config.footprints() || wv == null)
		{
			count = 0;
			next = 0;
			lastStep.clear();
			frame.printCount = 0;
			cells.clear(Cells.PRINTS);
			return;
		}
		int plane = client.getPlane();
		for (Player player : wv.players())
		{
			step(player, plane);
		}
		for (NPC npc : wv.npcs())
		{
			step(npc, plane);
		}
		cells.clear(Cells.PRINTS);
		for (int i = 0; i < count; ++i)
		{
			float px = packed[i * 8], pz = packed[i * 8 + 2];
			cells.mark(Cells.PRINTS, px - 24f, pz - 24f, px + 24f, pz + 24f);
		}
		renderer.setPrints(packed, count * 8);
		frame.printCount = count;
		frame.footprintStrength = 1f;
	}

	// A print lands each time an actor has moved about a third of a tile, a little to one side of
	// its path, the side alternating.
	private void step(Actor actor, int plane)
	{
		LocalPoint lp = actor.getLocalLocation();
		if (lp == null || actor.getWorldLocation().getPlane() != plane)
		{
			return;
		}
		float[] last = lastStep.get(actor);
		if (last == null)
		{
			lastStep.put(actor, new float[]{lp.getX(), lp.getY(), 0f});
			return;
		}
		float dx = lp.getX() - last[0];
		float dz = lp.getY() - last[1];
		float d2 = dx * dx + dz * dz;
		if (d2 < 48f * 48f)
		{
			return;
		}
		float len = (float) Math.sqrt(d2);
		float hx = dx / len, hz = dz / len;
		float side = last[2] <= 0f ? 1f : -1f;
		int o = next * 8;
		packed[o] = lp.getX() - hz * side * 9f;
		packed[o + 1] = Perspective.getTileHeight(client, lp, plane);
		packed[o + 2] = lp.getY() + hx * side * 9f;
		packed[o + 3] = frame.timeSeconds;
		packed[o + 4] = hx;
		packed[o + 5] = hz;
		packed[o + 6] = side;
		packed[o + 7] = 0f;
		next = (next + 1) % RtRenderer.MAX_PRINTS;
		count = Math.min(count + 1, RtRenderer.MAX_PRINTS);
		last[0] = lp.getX();
		last[1] = lp.getY();
		last[2] = side;
	}
}
