package rltx;

import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import net.runelite.api.Client;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.kit.KitType;
import rltx.scene.ModelPusher;
import rltx.scene.lights.LightDefinition;
import rltx.scene.lights.LightLibrary;
import rltx.scene.lights.SceneLights;
import rltx.vk.FrameParams;
import rltx.vk.RtRenderer;

/**
 * The local lights of a frame: 117 HD's light data, which objects and effects carry a flame,
 * the torch the character can be shown holding, and the upload of the nearest lights.
 */
final class LocalLights
{
	// The torch the character can be shown carrying: the lit torch item in the weapon slot, with
	// 117 HD's wall torch light following the flame of its model.
	private static final int HELD_TORCH = ItemID.TORCH_LIT + PlayerComposition.ITEM_OFFSET;
	private static final LightDefinition HELD_TORCH_LIGHT = heldTorchLight();

	private final Client client;
	private final RltxConfig config;
	private final Gson gson;
	private final FrameParams frame;
	private volatile LightLibrary library;
	private Integer heldTorchOriginal;
	boolean torchCarried;
	private int torchFlameFaces;
	private float torchX, torchTop, torchZ;

	LocalLights(Client client, RltxConfig config, Gson gson, FrameParams frame)
	{
		this.client = client;
		this.config = config;
		this.gson = gson;
		this.frame = frame;
	}

	private static LightDefinition heldTorchLight()
	{
		LightDefinition def = new LightDefinition();
		def.description = "Torch in hand";
		def.radius = 300f;
		def.strength = 10f;
		def.color = new JsonPrimitive("#fc9403");
		def.type = LightDefinition.Type.FLICKER;
		def.range = 20f;
		return def;
	}

	synchronized LightLibrary library()
	{
		if (library == null)
		{
			library = LightLibrary.load(gson);
		}
		return library;
	}

	// Objects that carry a light in 117 HD's data: their hot-coloured faces are flames.
	boolean hasLight(int objectId)
	{
		return library().byObject.containsKey(objectId);
	}

	/** Whether what is being drawn carries a light in 117 HD's data, so its hot faces are flames. */
	boolean lit(TileObject tileObject, Renderable renderable)
	{
		return tileObject != null && hasLight(tileObject.getId())
			|| renderable instanceof Projectile && library().byProjectile.containsKey(((Projectile) renderable).getId())
			|| renderable instanceof GraphicsObject && library().byGraphicsObject.containsKey(((GraphicsObject) renderable).getId());
	}

	// The server's appearance updates put the real weapon back, so the swap is redone each frame.
	void beforeRender()
	{
		torchCarried = config.heldTorch();
		torchFlameFaces = 0;
		if (torchCarried)
		{
			applyHeldTorch();
		}
	}

	private void applyHeldTorch()
	{
		Player local = client.getLocalPlayer();
		PlayerComposition composition = local == null ? null : local.getPlayerComposition();
		if (composition == null)
		{
			return;
		}
		int[] ids = composition.getEquipmentIds();
		int slot = KitType.WEAPON.getIndex();
		if (ids[slot] != HELD_TORCH)
		{
			heldTorchOriginal = ids[slot];
			ids[slot] = HELD_TORCH;
			composition.setHash();
		}
	}

	void restoreWeapon()
	{
		Player local = client.getLocalPlayer();
		PlayerComposition composition = local == null ? null : local.getPlayerComposition();
		if (composition != null && heldTorchOriginal != null)
		{
			int[] ids = composition.getEquipmentIds();
			int slot = KitType.WEAPON.getIndex();
			if (ids[slot] == HELD_TORCH)
			{
				ids[slot] = heldTorchOriginal;
				composition.setHash();
			}
		}
		heldTorchOriginal = null;
	}

	/** Where the character's torch flame was drawn this frame, for its light to follow. */
	void torchDrawn(ModelPusher pusher)
	{
		torchFlameFaces = pusher.flameFaces;
		torchX = pusher.flameX;
		torchTop = pusher.flameTop;
		torchZ = pusher.flameZ;
	}

	// The torch's light sits just above its flame so the flame's own faces do not shade the
	// ground; when the character was not drawn this frame it hangs at hand height over them.
	private void carryTorch(SceneLights lights)
	{
		Player local = client.getLocalPlayer();
		LocalPoint lp = torchCarried && local != null ? local.getLocalLocation() : null;
		if (lp == null)
		{
			lights.carry(null, 0f, 0f, 0f);
			return;
		}
		if (torchFlameFaces > 0)
		{
			lights.carry(HELD_TORCH_LIGHT, torchX, torchTop - 24f, torchZ);
			return;
		}
		float ground = Perspective.getTileHeight(client, lp, local.getWorldLocation().getPlane());
		lights.carry(HELD_TORCH_LIGHT, lp.getX(), ground - 180f, lp.getY());
	}

	/**
	 * Uploads this frame's local lights: the scene's fixed and object lights plus those following
	 * NPCs and the carried torch, nearest first.
	 *
	 * @param lights the top-level scene's lights, or null while no scene is loaded
	 */
	void fill(RtRenderer renderer, SceneLights lights)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (!config.localLights() || lights == null || wv == null)
		{
			frame.lightCount = 0;
			return;
		}
		carryTorch(lights);
		int count = lights.pack(wv.npcs(), client.getProjectiles(), wv.getGraphicsObjects(), client.getGameCycle(), library(),
			(lp, plane) -> Perspective.getTileHeight(client, lp, plane),
			frame.cameraX, frame.cameraY, frame.cameraZ, frame.timeSeconds, config.lightRange() / 100f);
		renderer.setLights(lights.packed(), count);
		frame.lightCount = count;
		// 117 HD's strengths are tuned for its light units, which run brighter than ours.
		frame.lightStrength = config.lightStrength() / 100f * 0.35f;
		frame.sampledLights = config.sampledLights();
	}

	void reset()
	{
		torchCarried = false;
	}
}
