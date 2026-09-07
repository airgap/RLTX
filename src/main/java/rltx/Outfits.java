package rltx;

import java.util.Arrays;
import java.util.function.Predicate;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;

/**
 * Watches the character's outfit, the kit and colours of their appearance, and once a change has
 * settled for a couple of ticks and they are standing still, asks for a portrait of it.
 */
final class Outfits
{
	private static final int SETTLE_TICKS = 2;

	private final Client client;
	private final RltxConfig config;
	/** Renders the portrait; false when the renderer cannot yet, so the request is kept for the next tick. */
	private final Predicate<Player> portrait;
	private int[] kit;
	private int[] colours;
	private int settled;
	private boolean pending;

	Outfits(Client client, RltxConfig config, Predicate<Player> portrait)
	{
		this.client = client;
		this.config = config;
		this.portrait = portrait;
	}

	/** Once a game tick, on the client thread. */
	void tick()
	{
		Player local = client.getLocalPlayer();
		PlayerComposition composition = local == null ? null : local.getPlayerComposition();
		if (composition == null)
		{
			return;
		}
		int[] nowKit = composition.getEquipmentIds();
		int[] nowColours = composition.getColors();
		boolean first = kit == null;
		boolean changed = !first && (!Arrays.equals(nowKit, kit) || !Arrays.equals(nowColours, colours));
		kit = nowKit.clone();
		colours = nowColours.clone();
		if (first)
		{
			return;
		}
		if (changed)
		{
			pending = config.outfitRenders();
			settled = 0;
			return;
		}
		if (!pending)
		{
			return;
		}
		// A standing pose, not mid-swing or mid-stride, and the change over for a moment.
		boolean still = local.getAnimation() == -1 && local.getPoseAnimation() == local.getIdlePoseAnimation();
		if (++settled >= SETTLE_TICKS && still && portrait.test(local))
		{
			pending = false;
		}
	}
}
