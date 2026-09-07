package rltx;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

/**
 * The interface chrome in the renderer's palette: the client's own sprites for the chatbox,
 * tabs, minimap frame, borders and buttons are read from the cache, regraded, and put back as
 * sprite overrides. Nothing is redrawn by hand, so every skin keeps the shapes players know.
 * Sprites carry no alpha, so where the chrome lies over the scene its translucency comes from
 * the opacity of the widgets that draw it.
 */
final class Chrome
{
	/** The sprites that make up the chrome, the set the client's own interface styles re-skin. */
	private static final int[] SPRITES = {
		0, 1, 2, 3, 4, 5, 6, 7, 169, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213,
		214, 215, 216, 217, 220, 221, 539, 540, 541, 542, 773, 774, 776, 779, 780, 782, 783, 788, 789, 790, 791, 792, 835,
		836, 898, 900, 901, 904, 907, 908, 909, 910, 1017, 1018, 1026, 1027, 1028, 1029, 1030, 1031, 1032, 1033, 1034,
		1035, 1036, 1037, 1038, 1039, 1053, 1071, 1072, 1173, 1174, 1175, 1176, 1177, 1180, 1181, 1182, 1196, 1197, 1198,
		1199, 1297, 1299, 1414, 1438, 1439, 1440, 1441, 1583, 1584, 1611, 1702, 1703, 1711, 1713, 2219, 2276, 2309, 3051,
		3052, 3053, 3054, 3055, 3057, 3058, 7431, 7432, 7433, 7434, 7436, 7437, 7438, 7439, 7440, 7441, 7442, 7443, 7444,
		7445, 7446, 7447, 7448, 7449, 7450, 7452, 7453,
	};

	/** The one colour every glass pane is drawn in, for the compositor to find and render as glass. */
	static final int GLASS_KEY = 0x0a0b10;
	private static final Set<Integer> SPRITE_SET = new HashSet<>();

	static
	{
		for (int id : SPRITES)
		{
			SPRITE_SET.add(id);
		}
	}

	private final Client client;
	private final RltxConfig config;
	private RltxConfig.Chrome applied = RltxConfig.Chrome.OFF;
	private final Set<Integer> installed = new HashSet<>();
	private int appliedOpacity;

	Chrome(Client client, RltxConfig config)
	{
		this.client = client;
		this.config = config;
	}

	/** On the client thread, once a tick: follows the setting, and picks up sprites as the cache yields them. */
	void apply()
	{
		RltxConfig.Chrome skin = config.chrome();
		if (skin != applied)
		{
			remove();
			applied = skin;
		}
		// Glass carries its own translucency in the compositor; the other skins fade their widgets.
		int opacity = skin == RltxConfig.Chrome.OFF || skin == RltxConfig.Chrome.GLASS ? 0 : Math.round(255f * config.chromeTransparency() / 100f);
		// The client rebuilds interfaces as they open, so the widgets are revisited every tick.
		if (opacity != 0 || appliedOpacity != 0)
		{
			for (Widget root : client.getWidgetRoots())
			{
				fade(root, opacity);
			}
			appliedOpacity = opacity;
		}
		if (skin == RltxConfig.Chrome.OFF || installed.size() == SPRITES.length)
		{
			return;
		}
		boolean changed = false;
		for (int id : SPRITES)
		{
			if (installed.contains(id))
			{
				continue;
			}
			SpritePixels[] frames = client.getSprites(client.getIndexSprites(), id, 0);
			if (frames == null || frames.length == 0 || frames[0] == null)
			{
				continue;
			}
			SpritePixels original = frames[0];
			int[] pixels = original.getPixels().clone();
			grade(pixels, skin);
			SpritePixels graded = client.createSpritePixels(pixels, original.getWidth(), original.getHeight());
			graded.setMaxWidth(original.getMaxWidth());
			graded.setMaxHeight(original.getMaxHeight());
			graded.setOffsetX(original.getOffsetX());
			graded.setOffsetY(original.getOffsetY());
			client.getSpriteOverrides().put(id, graded);
			installed.add(id);
			changed = true;
		}
		if (changed)
		{
			client.getWidgetSpriteCache().reset();
		}
	}

	/** On the client thread: puts the client's own sprites back, solid. */
	void remove()
	{
		if (appliedOpacity != 0)
		{
			for (Widget root : client.getWidgetRoots())
			{
				fade(root, 0);
			}
			appliedOpacity = 0;
		}
		if (installed.isEmpty())
		{
			return;
		}
		for (int id : installed)
		{
			client.getSpriteOverrides().remove(id);
		}
		installed.clear();
		client.getWidgetSpriteCache().reset();
	}

	// Sets the opacity of every graphic widget drawing a chrome sprite under this one.
	private static void fade(Widget widget, int opacity)
	{
		if (widget == null)
		{
			return;
		}
		if (widget.getType() == WidgetType.GRAPHIC && SPRITE_SET.contains(widget.getSpriteId()))
		{
			widget.setOpacity(opacity);
		}
		for (Widget[] group : Arrays.asList(widget.getStaticChildren(), widget.getDynamicChildren(), widget.getNestedChildren()))
		{
			if (group == null)
			{
				continue;
			}
			for (Widget child : group)
			{
				fade(child, opacity);
			}
		}
	}

	// Each opaque pixel is drawn toward grey, pushed through a contrast curve about the middle,
	// and tinted; a zero pixel is transparent and stays so, and a result that would be zero is
	// kept a shade above it for the same reason. Glass reads the stone's own bevels instead: its
	// lit ridges become gold edges and everything else the key colour the compositor renders as
	// a pane of liquid glass over the scene.
	private static void grade(int[] pixels, RltxConfig.Chrome skin)
	{
		if (skin == RltxConfig.Chrome.GLASS)
		{
			glass(pixels);
			return;
		}
		float desaturate, contrast, brightness, tintR, tintG, tintB;
		switch (skin)
		{
			case SLATE:
				desaturate = 0.8f;
				contrast = 1.15f;
				brightness = 0.85f;
				tintR = 0.66f;
				tintG = 0.72f;
				tintB = 0.82f;
				break;
			case OBSIDIAN:
				desaturate = 0.9f;
				contrast = 1.3f;
				brightness = 0.55f;
				tintR = 0.6f;
				tintG = 0.6f;
				tintB = 0.66f;
				break;
			case EMBER:
				desaturate = 0.6f;
				contrast = 1.1f;
				brightness = 0.8f;
				tintR = 1.0f;
				tintG = 0.76f;
				tintB = 0.54f;
				break;
			default:
				return;
		}
		for (int i = 0; i < pixels.length; ++i)
		{
			int p = pixels[i];
			if (p == 0)
			{
				continue;
			}
			float r = (p >> 16 & 0xff) / 255f;
			float g = (p >> 8 & 0xff) / 255f;
			float b = (p & 0xff) / 255f;
			float l = 0.299f * r + 0.587f * g + 0.114f * b;
			r = channel(r, l, desaturate, contrast, brightness * tintR);
			g = channel(g, l, desaturate, contrast, brightness * tintG);
			b = channel(b, l, desaturate, contrast, brightness * tintB);
			int out = Math.round(r * 255f) << 16 | Math.round(g * 255f) << 8 | Math.round(b * 255f);
			pixels[i] = out == 0 ? 1 : out;
		}
	}

	private static void glass(int[] pixels)
	{
		for (int i = 0; i < pixels.length; ++i)
		{
			int p = pixels[i];
			if (p == 0)
			{
				continue;
			}
			float l = (0.299f * (p >> 16 & 0xff) + 0.587f * (p >> 8 & 0xff) + 0.114f * (p & 0xff)) / 255f;
			if (l > 0.58f)
			{
				float gold = 0.75f + 0.5f * (l - 0.58f);
				int r = Math.round(Math.min(1f, 0.84f * gold) * 255f);
				int g = Math.round(Math.min(1f, 0.70f * gold) * 255f);
				int b = Math.round(Math.min(1f, 0.40f * gold) * 255f);
				pixels[i] = r << 16 | g << 8 | b;
			}
			else
			{
				pixels[i] = GLASS_KEY;
			}
		}
	}

	private static float channel(float c, float l, float desaturate, float contrast, float gain)
	{
		float grey = c + (l - c) * desaturate;
		float curved = (grey - 0.5f) * contrast + 0.5f;
		return Math.min(1f, Math.max(0f, curved * gain));
	}
}
