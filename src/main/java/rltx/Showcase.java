package rltx;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;

/**
 * The showcase: one switch that pushes every quality setting to its top for showing the renderer
 * off, and puts the player's own values back when it goes off. What it replaced is also kept in
 * the configuration, so a client that closes with the showcase on restores them at the next start.
 */
final class Showcase
{
	static final String REPLACED_KEY = "showcaseReplaced";
	private static final Type MAP = new TypeToken<Map<String, String>>()
	{
	}.getType();

	/** Quality levers only; the settings that are a look rather than a level are left alone. */
	static final Map<String, String> OVERRIDES = new LinkedHashMap<>();

	static
	{
		OVERRIDES.put("bounces", "4");
		OVERRIDES.put("denoiserPasses", "5");
		OVERRIDES.put("historyFrames", "128");
		OVERRIDES.put("dynamicHistoryFrames", "64");
		OVERRIDES.put("temporal", "true");
		OVERRIDES.put("shadows", "true");
		OVERRIDES.put("sampledLights", "false");
		OVERRIDES.put("glossyReflections", "true");
		OVERRIDES.put("caustics", "true");
		OVERRIDES.put("textures", "true");
		OVERRIDES.put("terrainTextures", "true");
		OVERRIDES.put("terrainSmoothing", "true");
		OVERRIDES.put("terrainBump", "200");
		OVERRIDES.put("bumpStrength", "100");
		OVERRIDES.put("textureDisplacement", "true");
		OVERRIDES.put("physicalSky", "true");
		OVERRIDES.put("stars", "true");
		OVERRIDES.put("cloudShadows", "true");
		OVERRIDES.put("roofOcclusion", "true");
		OVERRIDES.put("wildlife", "true");
		OVERRIDES.put("fireflies", "true");
		OVERRIDES.put("dustMotes", "true");
		OVERRIDES.put("rainbows", "true");
		OVERRIDES.put("heatShimmer", "true");
		OVERRIDES.put("smoke", "true");
		OVERRIDES.put("footprints", "true");
		OVERRIDES.put("puddles", "true");
		OVERRIDES.put("rainRipples", "true");
		OVERRIDES.put("runoff", "true");
		OVERRIDES.put("foliageWind", "true");
		OVERRIDES.put("foliageWindRange", "32");
		OVERRIDES.put("waveGeometry", "true");
		OVERRIDES.put("waterRipples", "true");
		OVERRIDES.put("antialias", "true");
		OVERRIDES.put("renderScale", "100");
		OVERRIDES.put("dlss", "DLAA");
		OVERRIDES.put("drawDistance", "92");
	}

	private final Presets presets;
	private final ConfigManager configManager;
	private final Gson gson;
	private Map<String, String> replaced;

	Showcase(Presets presets, ConfigManager configManager, Gson gson)
	{
		this.presets = presets;
		this.configManager = configManager;
		this.gson = gson;
	}

	/** At start: a showcase left on by a client that closed is undone, so the player's values come back. */
	void recover()
	{
		String kept = configManager.getConfiguration(RltxConfig.GROUP, REPLACED_KEY);
		if (kept == null)
		{
			return;
		}
		presets.apply(gson.fromJson(kept, MAP));
		configManager.unsetConfiguration(RltxConfig.GROUP, REPLACED_KEY);
		configManager.setConfiguration(RltxConfig.GROUP, "showcase", false);
	}

	synchronized void set(boolean on)
	{
		if (on && replaced == null)
		{
			replaced = presets.captureKeys(OVERRIDES.keySet());
			configManager.setConfiguration(RltxConfig.GROUP, REPLACED_KEY, gson.toJson(replaced, MAP));
			presets.apply(OVERRIDES);
		}
		else if (!on && replaced != null)
		{
			presets.apply(replaced);
			replaced = null;
			configManager.unsetConfiguration(RltxConfig.GROUP, REPLACED_KEY);
		}
	}

	boolean on()
	{
		return replaced != null;
	}
}
