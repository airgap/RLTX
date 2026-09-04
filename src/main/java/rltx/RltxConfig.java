package rltx;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;
import rltx.sky.Skybox;
import rltx.sky.WeatherState;

@ConfigGroup(RltxConfig.GROUP)
public interface RltxConfig extends Config
{
	String GROUP = "rltx";

	@ConfigSection(
		name = "Sun",
		description = "Directional sunlight",
		position = 0
	)
	String sunSection = "sun";

	@ConfigSection(
		name = "Sky and indirect",
		description = "Sky light, bounced light and exposure",
		position = 1
	)
	String skySection = "sky";

	@ConfigSection(
		name = "Temporal",
		description = "Frame-to-frame accumulation of the sampled lighting",
		position = 3
	)
	String temporalSection = "temporal";

	@ConfigSection(
		name = "Weather",
		description = "Clouds, fog, rain, snow and storms, from the real weather at your location or a preset",
		position = 2
	)
	String weatherSection = "weather";

	@ConfigSection(
		name = "Camera",
		description = "Antialiasing and depth of field",
		position = 4
	)
	String cameraSection = "camera";

	@ConfigSection(
		name = "Surfaces",
		description = "Textures and water",
		position = 5
	)
	String surfacesSection = "surfaces";

	@ConfigSection(
		name = "Debug",
		description = "Development toggles",
		position = 6,
		closedByDefault = true
	)
	String debugSection = "debug";

	enum SunMode
	{
		REAL_TIME("Real time and place"),
		MANUAL("Manual azimuth and elevation");

		private final String label;

		SunMode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		keyName = "sunMode",
		name = "Sun position",
		description = "Follow the real sun for the latitude, longitude and clock below, or use the manual azimuth and elevation",
		section = sunSection,
		position = -5
	)
	default SunMode sunMode()
	{
		return SunMode.REAL_TIME;
	}

	@Range(min = -90, max = 90)
	@Units("°")
	@ConfigItem(
		keyName = "latitude",
		name = "Latitude",
		description = "Degrees north of the equator, negative for south",
		section = sunSection,
		position = -4
	)
	default int latitude()
	{
		return 45;
	}

	@Range(min = -180, max = 180)
	@Units("°")
	@ConfigItem(
		keyName = "longitude",
		name = "Longitude",
		description = "Degrees east of Greenwich, negative for west. The default is estimated from the system time zone.",
		section = sunSection,
		position = -3
	)
	default int longitude()
	{
		return (int) Math.round(java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000.0 * 15.0);
	}

	@Range(min = -12, max = 12)
	@Units(" h")
	@ConfigItem(
		keyName = "timeOffset",
		name = "Time offset",
		description = "Hours added to the clock when following real time, to preview another time of day",
		section = sunSection,
		position = -2
	)
	default int timeOffset()
	{
		return 0;
	}

	@Range(min = 0, max = 50)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "moonlight",
		name = "Moonlight",
		description = "Direct light at night as a fraction of the sun's intensity, cast from opposite the sun",
		section = sunSection,
		position = -1
	)
	default int moonlight()
	{
		return 25;
	}

	@Range(min = 0, max = 359)
	@Units("°")
	@ConfigItem(
		keyName = "sunAzimuth",
		name = "Azimuth",
		description = "Manual mode: compass direction the sunlight comes from. 0 is north, 90 is east.",
		section = sunSection,
		position = 0
	)
	default int sunAzimuth()
	{
		return 225;
	}

	@Range(min = 5, max = 89)
	@Units("°")
	@ConfigItem(
		keyName = "sunElevation",
		name = "Elevation",
		description = "Height of the sun above the horizon in manual mode. A skybox with a visible sun or moon overrides this so shadows match it.",
		section = sunSection,
		position = 1
	)
	default int sunElevation()
	{
		return 50;
	}

	@Range(min = 0, max = 300)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "sunIntensity",
		name = "Intensity",
		description = "Brightness of direct sunlight",
		section = sunSection,
		position = 2
	)
	default int sunIntensity()
	{
		return 100;
	}

	@Range(min = 0, max = 20)
	@Units("°")
	@ConfigItem(
		keyName = "sunSize",
		name = "Sun size",
		description = "Apparent diameter of the sun in degrees. Larger values give softer shadow edges; 0 gives hard shadows.",
		section = sunSection,
		position = 3
	)
	default int sunSize()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "shadows",
		name = "Ray traced shadows",
		description = "Trace a shadow ray toward the sun from every visible point",
		section = sunSection,
		position = 4
	)
	default boolean shadows()
	{
		return true;
	}

	@Range(max = 300)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "lightShafts",
		name = "Light shafts",
		description = "Sunlight scattered by the air, marched along every view ray with shadow rays toward the sun, so beams form through trees and openings. 0 disables.",
		section = sunSection,
		position = 5
	)
	default int lightShafts()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "skybox",
		name = "Skybox",
		description = "Sky image from the Fantasy Skybox pack, used as the background and as the sky light. A fixed sky does not move the sun; the 'follows time of day' entries switch with the sun position. Requires the pack folder below.",
		section = skySection,
		position = -3
	)
	default Skybox skybox()
	{
		return Skybox.NONE;
	}

	@ConfigItem(
		keyName = "skyboxDirectory",
		name = "Skybox pack folder",
		description = "Path to the Materials folder of the Fantasy Skybox pack",
		section = skySection,
		position = -2
	)
	default String skyboxDirectory()
	{
		return System.getProperty("user.home") + "/Downloads/Fantasy Skybox/Materials";
	}

	@Range(min = 0, max = 359)
	@Units("°")
	@ConfigItem(
		keyName = "skyboxRotation",
		name = "Skybox rotation",
		description = "Extra turn applied to the sky image. Images with a visible sun or moon are already aligned to the light direction.",
		section = skySection,
		position = -1
	)
	default int skyboxRotation()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "skyColor",
		name = "Sky colour",
		description = "Colour of the sky when no skybox is selected. Lights surfaces that can see the sky.",
		section = skySection,
		position = 0
	)
	default Color skyColor()
	{
		return new Color(135, 174, 235);
	}

	@Range(min = 0, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "skyIntensity",
		name = "Sky intensity",
		description = "Brightness of the sky light",
		section = skySection,
		position = 1
	)
	default int skyIntensity()
	{
		return 45;
	}

	@Range(min = 0, max = 4)
	@ConfigItem(
		keyName = "bounces",
		name = "Light bounces",
		description = "Diffuse bounces traced per pixel. One lights surfaces from their neighbours and the sky; two or more carry light around corners into enclosed spaces. 0 disables indirect light.",
		section = skySection,
		position = 2
	)
	default int bounces()
	{
		return 3;
	}

	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "ambient",
		name = "Ambient floor",
		description = "Light that reaches every surface regardless of occlusion",
		section = skySection,
		position = 3
	)
	default int ambient()
	{
		return 5;
	}

	@Range(min = 25, max = 400)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "exposure",
		name = "Exposure",
		description = "Scales the lit result before the filmic tone curve. 100 keeps midtones roughly as lit.",
		section = skySection,
		position = 4
	)
	default int exposure()
	{
		return 180;
	}

	@ConfigItem(
		keyName = "temporal",
		name = "Temporal accumulation",
		description = "Reuse lighting from previous frames where the same surface was visible. Off gives a noisy but ghost-free image.",
		section = temporalSection,
		position = -1
	)
	default boolean temporal()
	{
		return true;
	}

	@Range(min = 1, max = 128)
	@ConfigItem(
		keyName = "historyFrames",
		name = "History frames",
		description = "How many frames of lighting are averaged on static surfaces. More frames mean less noise and slower response to changes.",
		section = temporalSection,
		position = 0
	)
	default int historyFrames()
	{
		return 32;
	}

	@Range(min = 0, max = 5)
	@ConfigItem(
		keyName = "denoiserPasses",
		name = "Denoiser passes",
		description = "Edge-aware wavelet filter passes over the accumulated lighting. Each pass doubles the filter radius; 0 disables the denoiser.",
		section = temporalSection,
		position = 2
	)
	default int denoiserPasses()
	{
		return 4;
	}

	@Range(min = 1, max = 16)
	@ConfigItem(
		keyName = "denoiserStrength",
		name = "Denoiser strength",
		description = "How far the filter reaches across brightness differences, in standard deviations of the estimated noise. Higher smooths more and softens lighting detail.",
		section = temporalSection,
		position = 3
	)
	default int denoiserStrength()
	{
		return 4;
	}

	@Range(min = 1, max = 64)
	@ConfigItem(
		keyName = "dynamicHistoryFrames",
		name = "History frames on models",
		description = "Frames averaged on players, NPCs and animated objects",
		section = temporalSection,
		position = 1
	)
	default int dynamicHistoryFrames()
	{
		return 6;
	}

	@Range(min = 25, max = 184)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance",
		description = "Radius in tiles within which the client processes actors, animated objects and mouse picking. The ray tracer itself always covers the whole loaded scene.",
		section = debugSection,
		position = -1
	)
	default int drawDistance()
	{
		return 90;
	}

	enum FocusMode
	{
		PLAYER("Follow the player"),
		MANUAL("Fixed distance");

		private final String label;

		FocusMode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		keyName = "antialias",
		name = "Antialiasing",
		description = "Jitter each pixel's ray within the pixel every frame; the accumulation averages the samples into smooth edges",
		section = cameraSection,
		position = 0
	)
	default boolean antialias()
	{
		return true;
	}

	@Range(min = 0, max = 64)
	@ConfigItem(
		keyName = "aperture",
		name = "Aperture",
		description = "Lens radius in scene units, where a tile is 128. 0 keeps everything in focus; larger blurs more away from the focus distance.",
		section = cameraSection,
		position = 1
	)
	default int aperture()
	{
		return 0;
	}

	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "motionBlur",
		name = "Motion blur",
		description = "Shutter time as a fraction of the frame interval. Blurs camera movement and moving models; 0 disables it.",
		section = cameraSection,
		position = 4
	)
	default int motionBlur()
	{
		return 50;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "vignette",
		name = "Vignette",
		description = "Darkens the corners of the view. 0 disables.",
		section = cameraSection,
		position = 9
	)
	default int vignette()
	{
		return 25;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "bloom",
		name = "Bloom",
		description = "Glow bled out of anything brighter than white after exposure. 0 disables.",
		section = cameraSection,
		position = 10
	)
	default int bloom()
	{
		return 35;
	}

	@ConfigItem(
		keyName = "focusMode",
		name = "Focus",
		description = "Keep your character in focus, or focus at a fixed distance from the camera",
		section = cameraSection,
		position = 2
	)
	default FocusMode focusMode()
	{
		return FocusMode.PLAYER;
	}

	@Range(min = 1, max = 60)
	@ConfigItem(
		keyName = "focusDistance",
		name = "Focus distance",
		description = "Fixed-distance focus, in tiles from the camera",
		section = cameraSection,
		position = 3
	)
	default int focusDistance()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "textures",
		name = "Textures",
		description = "Sample the game's textures on textured faces instead of their average colour",
		section = surfacesSection,
		position = 0
	)
	default boolean textures()
	{
		return false;
	}

	@ConfigItem(
		keyName = "unlitColours",
		name = "Remove baked shading",
		description = "Reverse the vanilla renderer's fixed-direction shading out of model and terrain colours so only the ray traced lighting shapes them. Approximate for models.",
		section = surfacesSection,
		position = -1
	)
	default boolean unlitColours()
	{
		return true;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "bumpStrength",
		name = "Texture relief",
		description = "Treats the brightness of a texture as height and tilts the lighting normal across it, giving brick and stone textures relief. Only applies with textures on. 0 disables.",
		section = surfacesSection,
		position = 1
	)
	default int bumpStrength()
	{
		return 35;
	}

	@ConfigItem(
		keyName = "water",
		name = "Reflective water",
		description = "Render water tiles as a reflective surface with animated waves and a sun glint",
		section = surfacesSection,
		position = 1
	)
	default boolean water()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "waveStrength",
		name = "Wave strength",
		description = "How much the waves tilt the water surface; 0 is a flat mirror",
		section = surfacesSection,
		position = 2
	)
	default int waveStrength()
	{
		return 40;
	}

	@ConfigItem(
		keyName = "cullBackfaces",
		name = "Cull back faces",
		description = "Skip triangles facing away from the camera, matching the vanilla renderer",
		section = debugSection,
		position = 0
	)
	default boolean cullBackfaces()
	{
		return true;
	}

	@ConfigItem(
		keyName = "loginPattern",
		name = "Test pattern on login screen",
		description = "Run the Vulkan pass with a synthetic pattern while logged out, to verify the Vulkan to OpenGL handoff",
		section = debugSection,
		position = 1
	)
	default boolean loginPattern()
	{
		return false;
	}

	enum WeatherMode
	{
		REAL_TIME, MANUAL, OFF
	}

	@ConfigItem(
		keyName = "weatherMode",
		name = "Weather",
		description = "Real weather at the Sun section's latitude and longitude, fetched from Open-Meteo every 10 minutes; a chosen preset; or none.",
		section = weatherSection,
		position = 0
	)
	default WeatherMode weatherMode()
	{
		return WeatherMode.REAL_TIME;
	}

	@ConfigItem(
		keyName = "weatherPreset",
		name = "Preset",
		description = "Conditions used when the weather is set manually.",
		section = weatherSection,
		position = 1
	)
	default WeatherState.Preset weatherPreset()
	{
		return WeatherState.Preset.RAIN;
	}

	@Range(max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "precipitation",
		name = "Precipitation density",
		description = "Scales how much rain and snow falls for the given conditions.",
		section = weatherSection,
		position = 2
	)
	default int precipitation()
	{
		return 100;
	}

	@Range(max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "fogAmount",
		name = "Fog",
		description = "Scales the distance fog of foggy, rainy and snowy conditions.",
		section = weatherSection,
		position = 3
	)
	default int fogAmount()
	{
		return 100;
	}

	@Range(max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "mist",
		name = "Swamp mist",
		description = "Low mist drifting over swamp water and the ground around it. 0 disables.",
		section = weatherSection,
		position = 4
	)
	default int mist()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "lightning",
		name = "Lightning",
		description = "Flashes of light during thunderstorms.",
		section = weatherSection,
		position = 5
	)
	default boolean lightning()
	{
		return true;
	}
}
