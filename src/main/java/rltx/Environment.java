package rltx;

import com.google.gson.Gson;
import java.awt.Color;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import okhttp3.OkHttpClient;
import org.lwjgl.system.MemoryUtil;
import rltx.sky.Atmosphere;
import rltx.sky.GeoLocation;
import rltx.sky.LunarPosition;
import rltx.sky.Season;
import rltx.sky.Sidereal;
import rltx.sky.Skybox;
import rltx.sky.SkyboxLoader;
import rltx.sky.SolarPosition;
import rltx.sky.StarMap;
import rltx.sky.WeatherService;
import rltx.sky.WeatherState;
import rltx.vk.FrameParams;
import rltx.vk.RtRenderer;

/**
 * The world outside the scene: the sun and moon from the clock and place, the sky as a skybox,
 * a scattered-light map or a flat colour, the stars, the weather and the season, and the
 * exposure. Fills the frame's lighting settings each frame and loads the sky's images off the
 * client thread.
 */
@Slf4j
final class Environment
{
	private static final WeatherState NO_WEATHER = new WeatherState();

	private final Client client;
	private final ClientThread clientThread;
	private final RltxConfig config;
	private final ConfigManager configManager;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final Cinema cinema;
	private final FrameParams frame;
	// The renderer the sky's images are uploaded to, while it lives.
	private RtRenderer renderer;

	private WeatherService weatherService;
	private GeoLocation geoLocation;
	final WeatherState weatherNow = new WeatherState();
	private WeatherState weatherTarget = NO_WEATHER;
	private float wetness, snowCover, flash;
	/** Seconds the weather stepped by this frame, for whatever else moves with it. */
	float weatherDt;
	private long lastWeatherNanos;
	private final Random lightningRandom = new Random();

	private volatile float[] skyHorizon;
	private volatile float[] skyHorizonRing;
	private volatile boolean skyboxLoaded;
	private volatile Skybox requestedSkybox;
	private double sunAzimuthNow, sunElevationNow;
	private Skybox.Phase phaseNow;
	private volatile double skyboxSunAzimuth = Double.NaN;
	private volatile double skyboxSunElevation = Double.NaN;
	private volatile boolean starMapLoaded;

	// The scattered-light sky is recomputed off the client thread whenever the light has moved
	// or the haze has changed enough to show, and uploaded when ready.
	private volatile boolean atmosphereLoaded;
	private volatile float[] atmosphereMap;
	private boolean atmosphereBusy;
	private float atmosphereX, atmosphereY, atmosphereZ, atmosphereIntensity = -1f, atmosphereHaze;

	private float autoExposureLevel = 1f;

	Environment(Client client, ClientThread clientThread, RltxConfig config, ConfigManager configManager, OkHttpClient okHttpClient, Gson gson,
		Cinema cinema, FrameParams frame)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.configManager = configManager;
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.cinema = cinema;
		this.frame = frame;
	}

	/** Takes a freshly created renderer and starts loading the star map into it. */
	void attach(RtRenderer renderer)
	{
		this.renderer = renderer;
		loadStarMap();
	}

	/** Forgets the renderer and everything a new one must be given again on the next start. */
	void detach()
	{
		renderer = null;
		skyboxLoaded = false;
		starMapLoaded = false;
		atmosphereLoaded = false;
		atmosphereMap = null;
		atmosphereIntensity = -1f;
		requestedSkybox = null;
		skyHorizon = null;
		skyHorizonRing = null;
		skyboxSunAzimuth = Double.NaN;
		skyboxSunElevation = Double.NaN;
		lastWeatherNanos = 0;
		autoExposureLevel = 1f;
		wetness = 0f;
		snowCover = 0f;
		flash = 0f;
	}

	/** The next frame resolves the skybox choice against the time of day and reloads it. */
	void reloadSkybox()
	{
		requestedSkybox = null;
	}

	// Real time and place takes the machine's own location; the other modes take the settings.
	double latitude()
	{
		if (config.sunMode() != RltxConfig.SunMode.REAL_TIME)
		{
			return config.latitude();
		}
		if (geoLocation == null)
		{
			geoLocation = new GeoLocation(okHttpClient, gson, config.latitude());
		}
		geoLocation.poll();
		return geoLocation.latitude();
	}

	double longitude()
	{
		if (config.sunMode() != RltxConfig.SunMode.REAL_TIME)
		{
			return config.longitude();
		}
		if (geoLocation == null)
		{
			geoLocation = new GeoLocation(okHttpClient, gson, config.latitude());
		}
		geoLocation.poll();
		return geoLocation.longitude();
	}

	// The season the scene is coloured for: 0 none, 1 spring to 4 winter, by the setting or the
	// real date for the machine's hemisphere. Progress is held to twentieths so the static scene
	// is only rebuilt every few days as the season advances.
	int seasonKind()
	{
		RltxConfig.SeasonMode mode = config.seasonMode();
		switch (mode)
		{
			case OFF:
				return 0;
			case REAL_DATE:
				return Season.at(System.currentTimeMillis(), latitude()).kind.ordinal() + 1;
			default:
				return mode.ordinal();
		}
	}

	float seasonProgress()
	{
		float progress = config.seasonMode() == RltxConfig.SeasonMode.REAL_DATE ? Season.at(System.currentTimeMillis(), latitude()).progress : 0.5f;
		return Math.round(progress * 20f) / 20f;
	}

	private void updateAtmosphere(float intensity)
	{
		if (!config.physicalSky() || !frame.proceduralSky || atmosphereBusy)
		{
			return;
		}
		float dx = frame.sunX - atmosphereX, dy = frame.sunY - atmosphereY, dz = frame.sunZ - atmosphereZ;
		boolean moved = dx * dx + dy * dy + dz * dz > 0.003f * 0.003f;
		if (!moved && Math.abs(intensity - atmosphereIntensity) < 0.02f && Math.abs(frame.fogAmount - atmosphereHaze) < 0.03f)
		{
			return;
		}
		float lx = frame.sunX, ly = frame.sunY, lz = frame.sunZ, haze = frame.fogAmount;
		atmosphereX = lx;
		atmosphereY = ly;
		atmosphereZ = lz;
		atmosphereIntensity = intensity;
		atmosphereHaze = haze;
		atmosphereBusy = true;
		Thread worker = new Thread(() ->
		{
			float[] map = Atmosphere.render(lx, ly, lz, intensity, haze);
			clientThread.invoke(() ->
			{
				atmosphereBusy = false;
				if (renderer == null)
				{
					return;
				}
				ByteBuffer pixels = MemoryUtil.memAlloc(map.length * Float.BYTES);
				try
				{
					pixels.asFloatBuffer().put(map);
					renderer.setAtmosphere(Atmosphere.WIDTH, Atmosphere.HEIGHT, pixels);
				}
				finally
				{
					MemoryUtil.memFree(pixels);
				}
				atmosphereMap = map;
				atmosphereLoaded = true;
			});
		}, "rltx-atmosphere");
		worker.setDaemon(true);
		worker.start();
	}

	// Rendering the catalogue takes a moment, so it happens off the client thread; the upload
	// then joins the client thread where all Vulkan work happens.
	private void loadStarMap()
	{
		Thread loader = new Thread(() ->
		{
			ByteBuffer pixels = StarMap.render();
			clientThread.invoke(() ->
			{
				try
				{
					if (renderer != null)
					{
						renderer.setStarMap(StarMap.WIDTH, StarMap.HEIGHT, pixels);
						starMapLoaded = true;
					}
				}
				finally
				{
					MemoryUtil.memFree(pixels);
				}
			});
		}, "rltx-stars");
		loader.setDaemon(true);
		loader.start();
	}

	// Decodes on a worker thread, then uploads on the client thread where all Vulkan work happens.
	private void loadSkybox(Skybox choice)
	{
		requestedSkybox = choice;
		if (choice == Skybox.NONE)
		{
			skyboxLoaded = false;
			return;
		}
		Path file = Paths.get(config.skyboxDirectory(), choice.getFolder(), choice.getFile());
		Skybox twin = choice.twin();
		Path twinFile = twin == null ? null : Paths.get(config.skyboxDirectory(), twin.getFolder(), twin.getFile());
		Thread loader = new Thread(() ->
		{
			SkyboxLoader.Decoded decoded;
			double sunAzimuth;
			double sunElevation;
			try
			{
				decoded = SkyboxLoader.load(file);
				if (choice.isBodyless())
				{
					sunAzimuth = Double.NaN;
					sunElevation = Double.NaN;
				}
				else if (twinFile != null)
				{
					SkyboxLoader.Decoded unlit = SkyboxLoader.load(twinFile);
					try
					{
						double[] body = SkyboxLoader.sunByDifference(decoded, unlit);
						sunAzimuth = body[0];
						sunElevation = body[1];
					}
					finally
					{
						MemoryUtil.memFree(unlit.pixels);
					}
				}
				else
				{
					sunAzimuth = decoded.sunAzimuthDegrees;
					sunElevation = decoded.sunElevationDegrees;
				}
			}
			catch (IOException e)
			{
				log.warn("Skybox {} could not be loaded; using the flat sky colour", file, e);
				skyboxLoaded = false;
				return;
			}
			clientThread.invoke(() ->
			{
				try
				{
					if (renderer != null && requestedSkybox == choice)
					{
						renderer.setSkybox(decoded.width, decoded.height, decoded.pixels);
						skyHorizon = decoded.horizon;
						skyHorizonRing = decoded.horizonRing;
						skyboxSunAzimuth = sunAzimuth;
						skyboxSunElevation = sunElevation;
						skyboxLoaded = true;
						log.info("Skybox {} loaded ({}x{}), sun in image at {}", choice, decoded.width, decoded.height,
							Double.isNaN(sunAzimuth) ? "none" : String.format("%.0f°", sunAzimuth));
					}
				}
				finally
				{
					MemoryUtil.memFree(decoded.pixels);
				}
			});
		}, "rltx-skybox-load");
		loader.start();
	}

	// Sun direction from the clock and place, or from the manual settings. Direct light fades
	// through twilight and warms near the horizon; at night a dim moon stands opposite the sun.
	private void fillSun()
	{
		double azimuth;
		double elevation;
		boolean pathTime = cinema.pathTime();
		long now = pathTime ? cinema.pathNow() : cinema.clock();
		boolean realTime = config.sunMode() != RltxConfig.SunMode.MANUAL;
		if (realTime)
		{
			SolarPosition sun = SolarPosition.compute(now, latitude(), longitude());
			azimuth = sun.azimuthDegrees;
			elevation = sun.elevationDegrees;
		}
		else
		{
			azimuth = pathTime ? cinema.pathAzimuth() : config.sunAzimuth();
			elevation = pathTime ? cinema.pathElevation() : config.sunElevation();
		}
		// The fixed stars turn with the real clock whatever the sun setting.
		Sidereal.rotation(now, latitude(), longitude(), frame.starRotation);
		frame.starBrightness = config.stars() && starMapLoaded ? config.starBrightness() / 100f : 0f;
		frame.moonFraction = -1f;

		double daylight = Math.max(0.0, Math.min(1.0, (elevation + 2.0) / 8.0));
		double lightAzimuth;
		double lightElevation;
		if (daylight > 0.0)
		{
			lightAzimuth = azimuth;
			lightElevation = elevation;
			frame.sunIntensity = (float) (config.sunIntensity() / 100.0 * daylight);
			float warmth = (float) Math.max(0.0, Math.min(1.0, elevation / 20.0));
			frame.sunR = 1.0f;
			frame.sunG = 0.55f + 0.45f * warmth;
			frame.sunB = 0.30f + 0.70f * warmth;
		}
		else if (realTime && config.stars())
		{
			// Moonlight from where the moon really is, as bright as its phase allows, and none
			// once it has set; the sun's direction below the horizon shades the disc.
			LunarPosition moon = LunarPosition.compute(now);
			float[] toMoon = Sidereal.worldDirection(now, latitude(), longitude(), moon.raDegrees, moon.decDegrees);
			lightElevation = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, -toMoon[1]))));
			lightAzimuth = Math.toDegrees(Math.atan2(toMoon[0], toMoon[2]));
			frame.sunIntensity = lightElevation > 0.0 ? (float) (config.sunIntensity() / 100.0 * config.moonlight() / 100.0 * (0.03 + 0.97 * moon.illuminatedFraction)) : 0f;
			frame.sunR = 0.60f;
			frame.sunG = 0.72f;
			frame.sunB = 1.00f;
			frame.moonFraction = (float) moon.illuminatedFraction;
			double sunAz = Math.toRadians(azimuth);
			double sunEl = Math.toRadians(elevation);
			frame.moonSunX = (float) (Math.sin(sunAz) * Math.cos(sunEl));
			frame.moonSunY = (float) -Math.sin(sunEl);
			frame.moonSunZ = (float) (Math.cos(sunAz) * Math.cos(sunEl));
		}
		else
		{
			lightAzimuth = azimuth + 180.0;
			lightElevation = -elevation;
			frame.sunIntensity = (float) (config.sunIntensity() / 100.0 * config.moonlight() / 100.0);
			frame.sunR = 0.60f;
			frame.sunG = 0.72f;
			frame.sunB = 1.00f;
		}
		// In manual mode a sky showing its own sun or moon puts the light where that body is
		// painted so shadows match what is seen. Real time and place is authoritative: the light
		// takes the computed height and the sky is only turned to follow its azimuth.
		if (config.sunMode() == RltxConfig.SunMode.MANUAL && skyboxLoaded && !Double.isNaN(skyboxSunElevation))
		{
			lightElevation = skyboxSunElevation;
		}
		double az = Math.toRadians(lightAzimuth);
		double el = Math.toRadians(lightElevation);
		// Scene space has north along +z, east along +x and up along -y.
		frame.sunX = (float) (Math.sin(az) * Math.cos(el));
		frame.sunY = (float) -Math.sin(el);
		frame.sunZ = (float) (Math.cos(az) * Math.cos(el));
		updateAtmosphere(daylight > 0.0 ? frame.sunIntensity : frame.sunIntensity * 0.1f);
		frame.physicalSky = config.physicalSky() && atmosphereLoaded;

		Skybox.Phase phase = elevation > 8.0 ? Skybox.Phase.DAY
			: elevation > -4.0 ? (azimuth < 180.0 ? Skybox.Phase.SUNRISE : Skybox.Phase.SUNSET)
			: Skybox.Phase.NIGHT;
		sunAzimuthNow = azimuth;
		sunElevationNow = elevation;
		// The real-time modes keep the manual sliders in step with the computed sun, so the panel
		// shows where it is and switching to Manual freezes it there.
		if (config.sunMode() != RltxConfig.SunMode.MANUAL)
		{
			int shownAzimuth = (((int) Math.round(azimuth)) % 360 + 360) % 360;
			int shownElevation = (int) Math.round(Math.max(-90.0, Math.min(90.0, elevation)));
			if (shownAzimuth != config.sunAzimuth())
			{
				configManager.setConfiguration(RltxConfig.GROUP, "sunAzimuth", shownAzimuth);
			}
			if (shownElevation != config.sunElevation())
			{
				configManager.setConfiguration(RltxConfig.GROUP, "sunElevation", shownElevation);
			}
		}
		phaseNow = phase;
		Skybox desired = config.proceduralSky() ? Skybox.NONE : config.skybox().resolve(phase);
		if (desired != requestedSkybox)
		{
			loadSkybox(desired);
		}
		frame.sunUp = (float) Math.sin(Math.toRadians(elevation));

		// Turn the sky so its painted sun or moon sits where the light comes from.
		double alignment = Double.isNaN(skyboxSunAzimuth) ? 0.0 : skyboxSunAzimuth - lightAzimuth;
		frame.skyboxRotation = (float) Math.toRadians(config.skyboxRotation() + alignment);
	}

	private void updateWeather()
	{
		long now = System.nanoTime();
		float dt = lastWeatherNanos == 0 ? 0f : Math.min((now - lastWeatherNanos) / 1e9f, 0.25f);
		lastWeatherNanos = now;
		weatherDt = dt;
		switch (config.weatherMode())
		{
			case REAL_TIME:
				if (weatherService == null)
				{
					weatherService = new WeatherService(okHttpClient, gson);
				}
				weatherService.poll(latitude(), longitude());
				WeatherState latest = weatherService.latest();
				if (latest != null)
				{
					weatherTarget = latest;
				}
				break;
			case MANUAL:
				weatherTarget = WeatherState.preset(config.weatherPreset()).seasonal(seasonKind(), seasonProgress());
				break;
			default:
				weatherTarget = NO_WEATHER;
				break;
		}
		// Conditions fade over a few seconds rather than snapping when a report or preset changes.
		weatherNow.approach(weatherTarget, Math.min(1f, dt / 4f));
		// Ground soaks quickly and dries slowly; snow settles and melts likewise.
		float rainTarget = weatherNow.rain > 0.05f ? 1f : 0f;
		wetness += (rainTarget - wetness) * Math.min(1f, dt / (rainTarget > wetness ? 15f : 90f));
		float snowTarget = weatherNow.snow > 0.05f ? 1f : 0f;
		snowCover += (snowTarget - snowCover) * Math.min(1f, dt / (snowTarget > snowCover ? 30f : 180f));
		// A flash every several seconds on average, gone within a few frames.
		if (weatherNow.storm && config.lightning() && lightningRandom.nextFloat() < dt / 5f)
		{
			flash = 1f;
		}
		else
		{
			flash *= Math.max(0f, 1f - dt * 12f);
			if (flash < 0.01f)
			{
				flash = 0f;
			}
		}
	}

	private void fillWeather(float[] horizon)
	{
		WeatherState w = weatherNow;
		float precipitation = config.precipitation() / 100f;
		frame.cloud = w.cloud;
		frame.rain = w.rain * precipitation;
		frame.snow = w.snow * precipitation;
		frame.fogAmount = Math.max(w.fog, 0.2f * w.rain + 0.3f * w.snow) * config.fogAmount() / 100f;
		frame.wetness = wetness;
		frame.snowCover = snowCover;
		frame.flash = flash;
		frame.mist = config.mist() / 100f;
		frame.mistEverywhere = config.mistEverywhere();
		frame.mistIndoors = config.mistIndoors();
		frame.fireflies = config.fireflies();
		frame.dustMotes = config.dustMotes();
		frame.wildlife = config.wildlife();
		frame.rainbows = config.rainbows();
		frame.focusPeaking = config.focusPeaking();
		frame.roofOcclusion = config.roofOcclusion();
		frame.heatShimmer = config.heatShimmer();
		frame.latitude = (float) latitude();
		RltxConfig.AuroraMode auroraMode = config.aurora();
		frame.auroraWeight = auroraMode == RltxConfig.AuroraMode.ALWAYS ? 1f
			: auroraMode == RltxConfig.AuroraMode.REAL ? smoothstep(50f, 65f, (float) Math.abs(latitude())) : 0f;
		frame.season = seasonKind();
		frame.seasonProgress = seasonProgress();
		// Leaves fall only in autumn, once the trees have begun to turn, most thickly late; petals
		// drift around the middle of spring.
		frame.leafFall = frame.season == 3 ? Math.max(0f, Math.min(1f, (frame.seasonProgress - 0.2f) / 0.5f)) : 0f;
		frame.petals = frame.season == 1 ? Math.max(0f, 1f - Math.abs(frame.seasonProgress - 0.45f) * 2.5f) : 0f;
		frame.lightShafts = config.lightShafts() / 100f;
		frame.timeSeconds = (float) ((System.nanoTime() / 1_000_000L % 3_600_000L) / 1000.0);
		// Wind blows away from its meteorological direction; full strength carries particles
		// about two tiles a second. The shader gets the accumulated displacement, not the
		// velocity, so a change of wind moves the air from where it is instead of jumping it.
		double to = Math.toRadians(w.windFromDegrees + 180.0);
		float speed = w.wind * 300f;
		frame.windVelocityX = (float) Math.sin(to) * speed;
		frame.windVelocityZ = (float) Math.cos(to) * speed;
		frame.windOffsetX = (frame.windOffsetX + (float) Math.sin(to) * speed * weatherDt) % 1048576f;
		frame.windOffsetZ = (frame.windOffsetZ + (float) Math.cos(to) * speed * weatherDt) % 1048576f;
		// Fog fades to the sky's horizon colour, greyed and dimmed by cloud the same way the
		// shader greys the sky, so fogged scenery meets the sky seamlessly.
		float lum = 0.2126f * horizon[0] + 0.7152f * horizon[1] + 0.0722f * horizon[2];
		float grey = 0.85f * w.cloud;
		float dim = 1f - 0.45f * w.cloud;
		frame.fogR = (horizon[0] + (lum - horizon[0]) * grey) * dim;
		frame.fogG = (horizon[1] + (lum - horizon[1]) * grey) * dim;
		frame.fogB = (horizon[2] + (lum - horizon[2]) * grey) * dim;
		// The same by compass eighth, so the fade meets the sky's own colour behind it.
		float[] ring = horizonRing(horizon);
		for (int s = 0; s < 8; ++s)
		{
			float l = 0.2126f * ring[s * 3] + 0.7152f * ring[s * 3 + 1] + 0.0722f * ring[s * 3 + 2];
			for (int c = 0; c < 3; ++c)
			{
				frame.fogRing[s * 4 + c] = (ring[s * 3 + c] + (l - ring[s * 3 + c]) * grey) * dim;
			}
			frame.fogRing[s * 4 + 3] = 1f;
		}
		// Sky light in linear radiance for things the final pass lights itself: the sky's own
		// horizon colour under its intensity and the cloud's dimming, or the flat sky colour.
		boolean pictured = skyboxLoaded || frame.proceduralSky;
		frame.skyAmbientR = (pictured ? horizon[0] * frame.skyR : frame.skyR) * dim * 0.6f;
		frame.skyAmbientG = (pictured ? horizon[1] * frame.skyG : frame.skyG) * dim * 0.6f;
		frame.skyAmbientB = (pictured ? horizon[2] * frame.skyB : frame.skyB) * dim * 0.6f;
		// Mist scatters nearly all the sun and sky light that reaches it, so it sits brighter than
		// the ground beneath, which reflects only its albedo's share. The final pass composites in
		// display space, so the colour goes through the same tone map as the scene.
		frame.mistR = tonemap(frame.sunR * frame.sunIntensity * 0.9f + horizon[0] * frame.skyR * 1.3f + frame.ambient);
		frame.mistG = tonemap(frame.sunG * frame.sunIntensity * 0.9f + horizon[1] * frame.skyG * 1.3f + frame.ambient);
		frame.mistB = tonemap(frame.sunB * frame.sunIntensity * 0.9f + horizon[2] * frame.skyB * 1.3f + frame.ambient);
	}

	// Horizon colour by eighth of the compass: the skybox's own ring, the scattered-light map's
	// horizon row, or the single colour repeated.
	private float[] horizonRing(float[] uniform)
	{
		float[] map = atmosphereMap;
		if (frame.physicalSky && map != null)
		{
			int row = (int) ((0.5 - 2.0 / 180.0) * Atmosphere.HEIGHT);
			float[] ring = new float[8 * 3];
			for (int i = 0; i < Atmosphere.WIDTH; ++i)
			{
				// Map columns and the shaders' sectors share one azimuth mapping.
				int sector = (int) Math.floor((i + 0.5) / Atmosphere.WIDTH * 8.0) & 7;
				int o = (row * Atmosphere.WIDTH + i) * 4;
				for (int c = 0; c < 3; ++c)
				{
					ring[sector * 3 + c] += map[o + c] * 8f / Atmosphere.WIDTH;
				}
			}
			return ring;
		}
		float[] skyRing = skyHorizonRing;
		if (!frame.proceduralSky && skyboxLoaded && skyRing != null)
		{
			return skyRing;
		}
		float[] ring = new float[8 * 3];
		for (int s = 0; s < 8; ++s)
		{
			System.arraycopy(uniform, 0, ring, s * 3, 3);
		}
		return ring;
	}

	// The analytic sky's colour at the horizon, matching proceduralSky() in trace.comp, for the
	// fog and distance fade to meet.
	private float[] proceduralHorizon()
	{
		float[] map = atmosphereMap;
		if (frame.physicalSky && map != null)
		{
			// The scattered-light map's row just above the horizon, averaged around the compass.
			int row = (int) ((0.5 - 2.0 / 180.0) * Atmosphere.HEIGHT);
			float[] h = new float[3];
			for (int i = 0; i < Atmosphere.WIDTH; ++i)
			{
				int o = (row * Atmosphere.WIDTH + i) * 4;
				h[0] += map[o];
				h[1] += map[o + 1];
				h[2] += map[o + 2];
			}
			for (int c = 0; c < 3; ++c)
			{
				h[c] /= Atmosphere.WIDTH;
			}
			return h;
		}
		float day = smoothstep(-0.12f, 0.15f, frame.sunUp);
		float low = (1f - smoothstep(0f, 0.35f, Math.abs(frame.sunUp))) * day;
		float[] h = new float[3];
		float[] night = {0.012f, 0.014f, 0.024f};
		float[] dayColor = {0.62f, 0.74f, 0.90f};
		float[] dusk = {0.95f, 0.45f, 0.2f};
		for (int i = 0; i < 3; ++i)
		{
			float base = night[i] + (dayColor[i] - night[i]) * day;
			h[i] = base + (dusk[i] - base) * low * 0.5f;
		}
		return h;
	}

	private static float smoothstep(float a, float b, float x)
	{
		float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
		return t * t * (3f - 2f * t);
	}

	// The meter reports the last frame's mean log luminance before exposure; the level chases
	// the exposure that would put that mean at middle grey, faster when darkening.
	private void fillExposure()
	{
		frame.autoExposure = config.autoExposure();
		if (!frame.autoExposure)
		{
			frame.exposure = config.exposure() / 100f;
			return;
		}
		double meanLog = renderer.averageLogLuminance();
		if (!Double.isNaN(meanLog))
		{
			float target = (float) (0.18 / Math.max(Math.exp(meanLog), 1e-4));
			target = Math.max(0.3f, Math.min(5f, target));
			float tau = target > autoExposureLevel ? 1.2f : 0.5f;
			autoExposureLevel += (target - autoExposureLevel) * (1f - (float) Math.exp(-weatherDt / tau));
		}
		frame.exposure = autoExposureLevel * config.exposure() / 100f;
	}

	// The character's eyes, a little above the head, for the line of sight test.
	private void fillEyes()
	{
		Player player = client.getLocalPlayer();
		LocalPoint lp = player == null ? null : player.getLocalLocation();
		if (!config.lineOfSight() || lp == null)
		{
			frame.unseenDarkness = 0f;
			return;
		}
		frame.eyeX = lp.getX();
		frame.eyeZ = lp.getY();
		frame.eyeY = Perspective.getTileHeight(client, lp, player.getWorldLocation().getPlane()) - 230f;
		frame.unseenDarkness = config.lineOfSightDarkness() / 100f;
	}

	// Same filmic curve as atrous.comp, so CPU-derived display colours match the scene.
	private float tonemap(float c)
	{
		c *= frame.exposure;
		return Math.max(0f, Math.min(1f, (c * (2.51f * c + 0.03f)) / (c * (2.43f * c + 0.59f) + 0.14f)));
	}

	/** Steps the weather and fills the frame's sun, sky, weather, season and shading settings. */
	void fill()
	{
		updateWeather();
		fillSun();
		// Cloud cover dims the sun and spreads it into soft shadows.
		frame.sunIntensity *= 1f - 0.92f * weatherNow.cloud;
		// A sky painted without its sun or moon has no body to cast shadows or glow, and the
		// light can be switched off outright for places the sky never reaches.
		if (!config.sunEnabled() || (skyboxLoaded && requestedSkybox != null && requestedSkybox.isBodyless()))
		{
			frame.sunIntensity = 0f;
		}
		frame.sunAngularRadius = (float) Math.toRadians(config.sunSize() / 2.0 * (1.0 + 6.0 * weatherNow.cloud));
		frame.sunDiscRadius = (float) Math.toRadians(config.sunDiscSize() / 10.0 / 2.0);
		frame.shadows = config.shadows();
		frame.bounces = config.bounces();
		frame.ambient = config.ambient() / 100f;
		frame.exposure = config.exposure() / 100f;
		frame.historyFrames = config.temporal() ? config.historyFrames() : 1;
		frame.dynamicHistoryFrames = config.temporal() ? config.dynamicHistoryFrames() : 1;
		frame.denoisePasses = config.denoiserPasses();
		frame.denoiseLuminance = config.denoiserStrength();
		frame.cullBackfaces = config.cullBackfaces();
		frame.textures = config.textures();
		frame.bumpStrength = config.bumpStrength() / 100f;
		frame.glossyReflections = config.glossyReflections();
		frame.surfaceGloss = config.surfaceGloss() / 100f * 0.3f;
		// Low roughness is a tight, glassy highlight; high spreads it wide.
		frame.surfaceGlossExponent = 300f - 288f * config.surfaceRoughness() / 100f;
		frame.emissiveStrength = config.emissiveStrength() / 100f;
		frame.caustics = config.caustics();
		frame.terrainTextures = config.terrainTextures();
		frame.terrainSmoothing = config.terrainSmoothing();
		frame.terrainBump = config.terrainBump() / 100f;
		frame.rainRipples = config.rainRipples();
		frame.rainSpeed = config.rainSpeed() / 100f;
		fillEyes();
		frame.rainLength = config.rainLength() / 100f;
		frame.puddles = config.puddles();
		frame.contrast = config.contrast() / 100f;
		frame.saturation = config.saturation() / 100f;
		frame.temperature = config.temperature() / 100f;
		frame.diffusion = config.softGlow() ? config.diffusion() / 100f : 0f;
		frame.diffusionRadius = config.diffusionRadius();
		frame.antialias = config.antialias();
		frame.water = config.water();
		// Wrapped where every integer scroll speed lands on a whole texture repeat.
		frame.gameCycle = client.getGameCycle() & 0x3FFF;
		// Scales each water type's own wave strength; 1 keeps 117 HD's values.
		frame.waveStrength = config.waveStrength() / 100f * 1.5f;
		frame.shutter = config.motionBlur() / 100f;
		frame.vignette = config.vignette() / 100f;
		frame.bloom = config.bloom() / 100f;
		frame.lensFlare = config.lensFlare() / 100f;
		frame.renderDistance = config.drawDistance() * Perspective.LOCAL_TILE_SIZE;
		frame.distanceFade = config.distanceFade() / 100f;
		frame.filmGrain = config.filmGrain() / 100f;
		frame.chromaticAberration = config.chromaticAberration() / 100f;
		frame.skybox = skyboxLoaded;
		// An overcast sky is greyed in the shader; it lights the scene more diffusely.
		float skyIntensity = config.skyIntensity() / 100f * (1f + 0.5f * weatherNow.cloud);
		Color sky = config.skyColor();
		frame.backgroundR = sky.getRed() / 255f;
		frame.backgroundG = sky.getGreen() / 255f;
		frame.backgroundB = sky.getBlue() / 255f;
		frame.proceduralSky = config.proceduralSky();
		frame.clouds = true;
		frame.cloudShadows = config.cloudShadows();
		frame.aerialPerspective = config.aerialPerspective() / 100f;
		if (skyboxLoaded || frame.proceduralSky)
		{
			frame.skyR = frame.skyG = frame.skyB = skyIntensity;
		}
		else
		{
			frame.skyR = frame.backgroundR * skyIntensity;
			frame.skyG = frame.backgroundG * skyIntensity;
			frame.skyB = frame.backgroundB * skyIntensity;
		}
		float[] horizon = frame.proceduralSky ? proceduralHorizon()
			: skyboxLoaded && skyHorizon != null ? skyHorizon : new float[]{frame.backgroundR, frame.backgroundG, frame.backgroundB};
		fillWeather(horizon);
		fillExposure();
	}

	void logSun()
	{
		log.debug("sun: mode={} azimuth={} elevation={} phase={} intensity={} skybox={}",
			config.sunMode(), Math.round(sunAzimuthNow), Math.round(sunElevationNow), phaseNow,
			String.format("%.2f", frame.sunIntensity), requestedSkybox);
	}
}
