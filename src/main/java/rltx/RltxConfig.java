package rltx;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import java.awt.event.KeyEvent;
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
		name = "Other plugins",
		description = "How RLTX draws what other plugins put on the scene",
		position = 6
	)
	String pluginsSection = "plugins";

	@ConfigSection(
		name = "Debug",
		description = "Development toggles",
		position = 7,
		closedByDefault = true
	)
	String debugSection = "debug";

	enum SunMode
	{
		REAL_TIME("Real time and place"),
		REAL_TIME_SET("Real time, chosen place"),
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
		keyName = "sunEnabled",
		name = "Sun and moon",
		description = "Untick to remove the sun and moon entirely, for caves and mines: no direct light, shadows, glints or beams, leaving the sky, local lights and the ambient floor.",
		section = sunSection,
		position = -6
	)
	default boolean sunEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sunMode",
		name = "Sun position",
		description = "Real time and place follows the real sun for where this machine actually is, found from its network address, and the real clock, overriding every other sun setting including a skybox's painted sun. Real time, chosen place uses the latitude, longitude and time offset below instead. Manual uses the azimuth and elevation.",
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
		description = "Degrees north of the equator, negative for south. Used by Real time, chosen place, and as the fallback before the real place is known.",
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
		description = "Degrees east of Greenwich, negative for west. Used by Real time, chosen place; the default is estimated from the system time zone.",
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
		description = "Hours added to the clock in Real time, chosen place, to preview another time of day. Real time and place ignores it.",
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

	@Range(min = -90, max = 90)
	@Units("°")
	@ConfigItem(
		keyName = "sunElevation",
		name = "Elevation",
		description = "Height of the sun above the horizon in manual mode. In manual mode a skybox with a visible sun or moon overrides this so shadows match it.",
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

	@Range(min = 2, max = 100)
	@Units(" ⁄10°")
	@ConfigItem(
		keyName = "sunDiscSize",
		name = "Sun disc size",
		description = "Apparent diameter of the sun or moon disc in the procedural sky, in tenths of a degree; the real sun is about 5. Visual only: shadow softness comes from Sun size.",
		section = sunSection,
		position = 6
	)
	default int sunDiscSize()
	{
		return 5;
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
		keyName = "localLights",
		name = "Local lights",
		description = "Torches, fires, lamps and glowing things from 117 HD's light data, each casting ray traced shadows.",
		section = skySection,
		position = -5
	)
	default boolean localLights()
	{
		return true;
	}

	@Range(max = 300)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "lightStrength",
		name = "Local light strength",
		description = "Scales the brightness of all local lights.",
		section = skySection,
		position = -4
	)
	default int lightStrength()
	{
		return 100;
	}

	@Range(min = 50, max = 400)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "lightRange",
		name = "Local light range",
		description = "Scales how far every local light reaches. Braziers and torches in mines carry further with more.",
		section = skySection,
		position = -4
	)
	default int lightRange()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "proceduralSky",
		name = "Procedural sky",
		description = "Replace the skybox with an analytic sky: a gradient that follows the sun through the day, a sun or moon disc, stars at night, and clouds from the weather. The light then always matches the sky.",
		section = skySection,
		position = -3
	)
	default boolean proceduralSky()
	{
		return false;
	}

	@ConfigItem(
		keyName = "cloudShadows",
		name = "Cloud shadows",
		description = "Drifting cloud shadows over the ground whenever the weather has cloud cover.",
		section = skySection,
		position = -2
	)
	default boolean cloudShadows()
	{
		return true;
	}

	@Range(max = 300)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "aerialPerspective",
		name = "Aerial perspective",
		description = "Air between you and distant scenery scatters blue into it by day and warmth at dusk, lifting far shadows before the fog hides them. 0 disables.",
		section = skySection,
		position = -1
	)
	default int aerialPerspective()
	{
		return 100;
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

	@Range(min = 16, max = 92)
	@Units(" tiles")
	@ConfigItem(
		keyName = "drawDistance",
		name = "Render distance",
		description = "How far the scene is drawn, in tiles. Zones beyond it leave the ray tracer, the last stretch fades into the horizon, and the client stops processing actors and clicks there.",
		section = cameraSection,
		position = 11
	)
	default int drawDistance()
	{
		return 90;
	}

	@Range(min = 5, max = 95)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "distanceFade",
		name = "Distance fade",
		description = "Share of the render distance over which scenery fades into the horizon. Larger values start the fade nearer and make the edge softer.",
		section = cameraSection,
		position = 12
	)
	default int distanceFade()
	{
		return 45;
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

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "filmGrain",
		name = "Film grain",
		description = "Fine random grain over the image, changing every frame and strongest in the shadows. 0 disables.",
		section = cameraSection,
		position = 13
	)
	default int filmGrain()
	{
		return 15;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "chromaticAberration",
		name = "Chromatic aberration",
		description = "Colour fringing that grows toward the edges of the view, as a lens would give. 0 disables.",
		section = cameraSection,
		position = 14
	)
	default int chromaticAberration()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "autoExposure",
		name = "Auto exposure",
		description = "Meter the scene's brightness and adapt the exposure to it over a second or two, like the eye. The Exposure slider then biases the result.",
		section = cameraSection,
		position = 8
	)
	default boolean autoExposure()
	{
		return true;
	}

	@ConfigItem(
		keyName = "softGlow",
		name = "Soft glow",
		description = "Blend a blurred copy of the whole frame back over it; the sliders below set how much and how wide.",
		section = cameraSection,
		position = 17
	)
	default boolean softGlow()
	{
		return true;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "diffusion",
		name = "Soft glow opacity",
		description = "A blurred copy of the whole frame blended back over it, softening contrast into a dreamy haze. 0 disables.",
		section = cameraSection,
		position = 18
	)
	default int diffusion()
	{
		return 25;
	}

	@Range(min = 8, max = 240)
	@Units(" px")
	@ConfigItem(
		keyName = "diffusionRadius",
		name = "Soft glow radius",
		description = "How widely the soft glow copy is blurred, in pixels at full resolution.",
		section = cameraSection,
		position = 19
	)
	default int diffusionRadius()
	{
		return 40;
	}

	@Range(min = 50, max = 150)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "contrast",
		name = "Contrast",
		description = "Colour grading: contrast around mid grey after tone mapping.",
		section = cameraSection,
		position = 15
	)
	default int contrast()
	{
		return 100;
	}

	@Range(max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "saturation",
		name = "Saturation",
		description = "Colour grading: 0 is monochrome, 100 leaves colours as rendered.",
		section = cameraSection,
		position = 16
	)
	default int saturation()
	{
		return 100;
	}

	@Range(min = -100, max = 100)
	@ConfigItem(
		keyName = "temperature",
		name = "Colour temperature",
		description = "Colour grading: negative cools the image toward blue, positive warms it toward orange.",
		section = cameraSection,
		position = 17
	)
	default int temperature()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "photoTiltEnabled",
		name = "Photo tilt",
		description = "Render from a pitch below the client's limit, by the angle set below; rendering only, so clicks do not line up while it is on.",
		section = cameraSection,
		position = 20
	)
	default boolean photoTiltEnabled()
	{
		return false;
	}

	@Range(min = -60, max = 60)
	@Units("°")
	@ConfigItem(
		keyName = "photoTilt",
		name = "Photo tilt angle",
		description = "Lowers the rendered camera by this many degrees below the client's pitch, pivoting about what you are looking at, for shots from nearer the ground. Rendering only: the client still picks clicks from its own camera, so use it for photos.",
		section = cameraSection,
		position = 20
	)
	default int photoTilt()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "freeCameraKey",
		name = "Free camera key",
		description = "Detaches the rendered camera where it is and lets you fly it: W A S D move, Q and E go down and up, Shift speeds up, middle-drag looks around. The game keeps running underneath; clicks do not line up while detached. Press again to reattach.",
		section = cameraSection,
		position = 24
	)
	default Keybind freeCameraKey()
	{
		return new Keybind(KeyEvent.VK_F10, 0);
	}

	@Range(min = 10, max = 500)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "freeCameraSpeed",
		name = "Free camera speed",
		description = "Flight speed of the detached camera; 100 is six tiles a second.",
		section = cameraSection,
		position = 25
	)
	default int freeCameraSpeed()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "lineOfSight",
		name = "Line of sight",
		description = "Darken what your character could not see from where they stand, so walls hide what lies beyond them. One extra ray per pixel from the character's eyes.",
		section = cameraSection,
		position = 22
	)
	default boolean lineOfSight()
	{
		return false;
	}

	@Range(min = 10, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "lineOfSightDarkness",
		name = "Unseen darkness",
		description = "How dark the unseen areas are drawn; 100 is black.",
		section = cameraSection,
		position = 23
	)
	default int lineOfSightDarkness()
	{
		return 85;
	}

	@ConfigItem(
		keyName = "heldTorch",
		name = "Torch in hand",
		description = "Shows your character carrying a lit torch in place of their weapon, with its own flickering light. Only you see it, and local lights must be on for the light.",
		section = cameraSection,
		position = 24
	)
	default boolean heldTorch()
	{
		return false;
	}

	@Range(max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "lensFlare",
		name = "Lens flare",
		description = "Glow, starburst, streak and ghost reflections from the sun or moon when it is in frame",
		section = cameraSection,
		position = 27
	)
	default int lensFlare()
	{
		return 60;
	}

	@Range(max = 2000)
	@ConfigItem(
		keyName = "photoBurst",
		name = "Photo burst",
		description = "Frames of the same view accumulated before a photo is saved, so it has no noise or denoiser blur. The game pauses while they render; 0 saves the frame as shown.",
		section = cameraSection,
		position = 26
	)
	default int photoBurst()
	{
		return 150;
	}

	@ConfigItem(
		keyName = "photoModeKey",
		name = "Photo mode key",
		description = "Hides every interface, overlay and text so only the scene shows. While hidden, click the top-left corner of the view to bring the interface back, or the bottom-right corner to save a photo to the screenshots folder under RLTX.",
		section = cameraSection,
		position = 21
	)
	default Keybind photoModeKey()
	{
		return new Keybind(KeyEvent.VK_F11, 0);
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
		keyName = "glossyReflections",
		name = "Glossy reflections",
		description = "Trace a reflection ray from every visible surface, blurred by its roughness, so polished and wet things mirror their surroundings.",
		section = surfacesSection,
		position = 2
	)
	default boolean glossyReflections()
	{
		return true;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "surfaceGloss",
		name = "Surface sheen",
		description = "Specular highlight and reflection strength of surfaces whose texture defines none, like a light clear coat. 0 leaves them matte.",
		section = surfacesSection,
		position = 3
	)
	default int surfaceGloss()
	{
		return 15;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "surfaceRoughness",
		name = "Surface roughness",
		description = "How blurred that sheen is: low is glassy, high is a broad soft highlight.",
		section = surfacesSection,
		position = 4
	)
	default int surfaceRoughness()
	{
		return 55;
	}

	@Range(max = 300)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "emissiveStrength",
		name = "Emissive surfaces",
		description = "Lava, fire capes and other textures 117 HD marks unlit glow with their own colour and feed the bloom. 0 disables.",
		section = surfacesSection,
		position = 5
	)
	default int emissiveStrength()
	{
		return 100;
	}

	@Range(min = 100, max = 250)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "treeScale",
		name = "Tree scale",
		description = "Draws trees larger about their base, purely visually: click boxes and collision stay where the game puts them, and a large canopy can reach into nearby roofs.",
		section = surfacesSection,
		position = 7
	)
	default int treeScale()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "terrainTextures",
		name = "Terrain textures",
		description = "Untextured ground keeps its vanilla colour but gains grass, dirt, sand, rock, gravel or snow grain chosen by that colour, with 117 HD's normal maps for relief.",
		section = surfacesSection,
		position = 8
	)
	default boolean terrainTextures()
	{
		return true;
	}

	@Range(max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "terrainBump",
		name = "Terrain relief",
		description = "Strength of the terrain textures' normal maps. 0 keeps the grain but flattens the relief.",
		section = surfacesSection,
		position = 9
	)
	default int terrainBump()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "terrainSmoothing",
		name = "Terrain smoothing",
		description = "Interpolate terrain colours and normals across tiles instead of flat facets, as the vanilla renderer does.",
		section = surfacesSection,
		position = 10
	)
	default boolean terrainSmoothing()
	{
		return false;
	}

	@ConfigItem(
		keyName = "caustics",
		name = "Water caustics",
		description = "Sunlight focused by the waves plays across the bed under clear water.",
		section = surfacesSection,
		position = 6
	)
	default boolean caustics()
	{
		return true;
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
		description = "Real weather for the sun's location, fetched every 10 minutes (weather data by Open-Meteo.com, CC BY 4.0); a chosen preset; or none.",
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

	@Range(min = 30, max = 300)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "rainSpeed",
		name = "Rain speed",
		description = "How fast drops fall; 100 is about seven tiles a second for the heaviest drops.",
		section = weatherSection,
		position = 5
	)
	default int rainSpeed()
	{
		return 100;
	}

	@Range(min = 30, max = 300)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "rainLength",
		name = "Rain streak length",
		description = "Length of each drop's streak; 100 is just under a tile.",
		section = weatherSection,
		position = 5
	)
	default int rainLength()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "rainRipples",
		name = "Rain ripples",
		description = "Rings spreading on water surfaces where raindrops land.",
		section = weatherSection,
		position = 6
	)
	default boolean rainRipples()
	{
		return true;
	}

	@ConfigItem(
		keyName = "runoff",
		name = "Rain runoff",
		description = "Simulate water on the terrain while it rains: it collects in hollows, runs down slopes into streams, and drains away afterwards, replacing the fixed puddle spots.",
		section = weatherSection,
		position = 7
	)
	default boolean runoff()
	{
		return true;
	}

	@ConfigItem(
		keyName = "puddles",
		name = "Puddles",
		description = "Dips in the ground fill with mirror-like water while it rains and dry out afterwards.",
		section = weatherSection,
		position = 7
	)
	default boolean puddles()
	{
		return true;
	}

	@ConfigItem(
		keyName = "foliageWind",
		name = "Foliage wind",
		description = "Trees, bushes and plants near the camera sway in the wind, their bases fixed and canopies moving.",
		section = weatherSection,
		position = 8
	)
	default boolean foliageWind()
	{
		return true;
	}

	@Range(max = 300)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "foliageWindStrength",
		name = "Foliage wind strength",
		description = "How far foliage bends; the weather's wind adds to it.",
		section = weatherSection,
		position = 9
	)
	default int foliageWindStrength()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "mistEverywhere",
		name = "Mist everywhere",
		description = "Lay the ground mist over the whole scene instead of only swamps and graveyards.",
		section = weatherSection,
		position = 4
	)
	default boolean mistEverywhere()
	{
		return false;
	}

	@ConfigItem(
		keyName = "fireflies",
		name = "Fireflies",
		description = "Fireflies drifting over swamps and graveyards on dry nights",
		section = weatherSection,
		position = 40
	)
	default boolean fireflies()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dustMotes",
		name = "Dust motes",
		description = "Specks of dust hanging in the air, seen where sunlight catches them",
		section = weatherSection,
		position = 41
	)
	default boolean dustMotes()
	{
		return true;
	}

	@ConfigItem(
		keyName = "smoke",
		name = "Smoke",
		description = "Smoke rising from chimneys and fires, carried by the wind",
		section = weatherSection,
		position = 42
	)
	default boolean smoke()
	{
		return true;
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

	@ConfigItem(
		keyName = "pathGlow",
		name = "Shortest Path glow",
		description = "With the Shortest Path plugin installed, draws its route as a ribbon of light on the ground with pulses running toward the destination, in place of its tile outlines.",
		section = pluginsSection,
		position = 0
	)
	default boolean pathGlow()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pathGlowColour",
		name = "Route glow colour",
		description = "Colour of the Shortest Path route glow",
		section = pluginsSection,
		position = 1
	)
	default Color pathGlowColour()
	{
		return new Color(255, 196, 96);
	}

	@Range(min = 10, max = 400)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "pathGlowStrength",
		name = "Route glow strength",
		description = "Brightness of the Shortest Path route glow",
		section = pluginsSection,
		position = 2
	)
	default int pathGlowStrength()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "markerGlow",
		name = "Ground marker glow",
		description = "Draws Ground Markers' tiles as pools of their colour lying on the ground, in place of the plugin's outlines.",
		section = pluginsSection,
		position = 3
	)
	default boolean markerGlow()
	{
		return true;
	}

	@Range(min = 10, max = 400)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "markerGlowStrength",
		name = "Marker glow strength",
		description = "Brightness of the ground marker pools",
		section = pluginsSection,
		position = 4
	)
	default int markerGlowStrength()
	{
		return 100;
	}

	@Range(min = 0, max = 400)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "npcGlow",
		name = "NPC highlight rim",
		description = "Gives NPCs highlighted by NPC Indicators, Slayer and other plugins a rim of light in their highlight colour. Those plugins' own hull, tile and outline drawing stays under their settings; 0 turns the rim off.",
		section = pluginsSection,
		position = 5
	)
	default int npcGlow()
	{
		return 100;
	}
}
