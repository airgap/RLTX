package rltx;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.function.BiPredicate;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.client.RuneLite;

/**
 * Watches the character's outfit, the kit and colours of their appearance, and once a change has
 * settled for a couple of ticks and they are standing still, asks for a portrait of it. Each
 * outfit is named by a hash of what it is made of, and one already on disk is not drawn again.
 */
final class Outfits
{
	private static final int SETTLE_TICKS = 2;
	static final File FOLDER = new File(RuneLite.SCREENSHOT_DIR, "RLTX/outfits");

	private final Client client;
	private final RltxConfig config;
	/** Renders the portrait to the named file; false when the renderer cannot yet, so the request is kept for the next tick. */
	private final BiPredicate<Player, String> portrait;
	private int[] kit;
	private int[] colours;
	private int settled;
	private boolean pending;

	Outfits(Client client, RltxConfig config, BiPredicate<Player, String> portrait)
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
		if (++settled < SETTLE_TICKS || !still)
		{
			return;
		}
		String name = "outfit-" + hash(kit, colours);
		if (new File(FOLDER, name + ".png").exists() || portrait.test(local, name))
		{
			pending = false;
		}
	}

	// A short digest of the kit and colours, the same for the same outfit on any day.
	private static String hash(int[] kit, int[] colours)
	{
		StringBuilder text = new StringBuilder();
		for (int k : kit)
		{
			text.append(k).append(',');
		}
		text.append('|');
		for (int c : colours)
		{
			text.append(c).append(',');
		}
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 6; ++i)
			{
				hex.append(String.format("%02x", digest[i]));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
