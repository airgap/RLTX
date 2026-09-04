package rltx;

import static org.lwjgl.opengl.GL43C.*;

import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.AffineTransform;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientUI;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryUtil;
import rltx.gl.GlCompositor;
import rltx.scene.GeometryBuffer;
import rltx.scene.ModelPusher;
import rltx.scene.MotionHistory;
import rltx.scene.Palette;
import rltx.scene.StaticScene;
import rltx.scene.StaticSceneBuilder;
import rltx.scene.TextureCutouts;
import rltx.sky.Skybox;
import rltx.sky.SkyboxLoader;
import rltx.sky.SolarPosition;
import rltx.vk.FrameParams;
import rltx.vk.RtRenderer;
import rltx.vk.VkContext;

@Slf4j
@PluginDescriptor(
	name = "RLTX",
	description = "Ray traced renderer: flat unlit triangles lit by Vulkan ray queries",
	tags = {"gpu", "graphics", "raytracing", "vulkan"},
	conflicts = {"GPU", "117 HD"}
)
public class RltxPlugin extends Plugin implements DrawCallbacks
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientUI clientUI;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private RltxConfig config;

	private Canvas canvas;
	private AWTContext awtContext;
	private boolean glReady;
	private GlCompositor compositor;
	private VkContext vk;
	private RtRenderer renderer;

	private volatile Palette palette;
	private volatile boolean skyboxLoaded;
	private volatile Skybox requestedSkybox;
	private boolean gameTexturesUploaded;
	private double sunAzimuthNow, sunElevationNow;
	private Skybox.Phase phaseNow;
	private volatile double skyboxSunAzimuth = Double.NaN;
	private volatile StaticScene pendingStatic;
	private boolean staticLoaded;
	private boolean staticDirty;

	private final GeometryBuffer dynamic = new GeometryBuffer(1 << 16);
	private final GeometryBuffer dynamicTranslucent = new GeometryBuffer(1 << 12);
	private final GeometryBuffer empty = new GeometryBuffer(1);
	private final ModelPusher framePusher = new ModelPusher();
	private final MotionHistory motion = new MotionHistory();
	private final FrameParams frame = new FrameParams();

	private boolean frameActive;
	private boolean sceneFramePending;
	private boolean glSignalPending;
	private boolean patternSampled;

	private int statDynamicCalls, statTempCalls, statFrames, statInactive, statSubScene;
	private long statSubmitNanos, statLastReport;

	@Provides
	RltxConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RltxConfig.class);
	}

	@Override
	protected void startUp()
	{
		clientThread.invoke(() ->
		{
			try
			{
				AWTContext.loadNatives();
				canvas = client.getCanvas();
				synchronized (canvas.getTreeLock())
				{
					if (!canvas.isValid())
					{
						return false;
					}
					awtContext = new AWTContext(canvas);
					awtContext.configurePixelFormat(0, 0, 0);
				}
				awtContext.createGLContext();
				canvas.setIgnoreRepaint(true);

				Configuration.SHARED_LIBRARY_EXTRACT_DIRECTORY.set("lwjgl-rl");
				GLCapabilities caps = GL.createCapabilities();
				glReady = true;
				log.info("OpenGL device: {} / {}", glGetString(GL_RENDERER), glGetString(GL_VERSION));
				awtContext.setSwapInterval(0);

				compositor = new GlCompositor(caps);
				vk = VkContext.create(compositor.deviceUuid());
				renderer = new RtRenderer(vk);
				compositor.importSemaphores(renderer.semaphoreVkDoneFd(), renderer.semaphoreGlDoneFd());

				client.setDrawCallbacks(this);
				// UNLIT_FACE_COLORS is deliberately absent: with it set from client start, actors stop
				// being handed to drawDynamic. Face colours fall back to the client's lit colours.
				client.setGpuFlags(DrawCallbacks.GPU | DrawCallbacks.ZBUF | DrawCallbacks.RENDER_THREADS(0));
				// Rebuilds the interface buffer with an alpha channel.
				client.resizeCanvas();

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					WorldView root = client.getTopLevelWorldView();
					Scene scene = root.getScene();
					loadScene(root, scene);
					swapScene(scene);
				}
			}
			catch (Throwable e)
			{
				log.error("Error starting RLTX", e);
				SwingUtilities.invokeLater(() ->
				{
					try
					{
						pluginManager.setPluginEnabled(this, false);
						pluginManager.stopPlugin(this);
					}
					catch (PluginInstantiationException ex)
					{
						log.error("error stopping plugin", ex);
					}
				});
				shutDown();
			}
			return true;
		});
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			client.setGpuFlags(0);
			client.setDrawCallbacks(null);

			if (renderer != null)
			{
				renderer.destroy();
				renderer = null;
			}
			if (vk != null)
			{
				vk.destroy();
				vk = null;
			}
			if (compositor != null)
			{
				compositor.destroy();
				compositor = null;
			}
			if (awtContext != null)
			{
				awtContext.destroy();
				awtContext = null;
			}
			glReady = false;
			if (canvas != null)
			{
				canvas.setIgnoreRepaint(false);
				canvas = null;
			}
			pendingStatic = null;
			staticLoaded = false;
			staticDirty = false;
			frameActive = false;
			sceneFramePending = false;
			glSignalPending = false;

			// Restores the interface buffer without an alpha channel.
			client.resizeCanvas();
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!RltxConfig.GROUP.equals(event.getGroup()) || renderer == null)
		{
			return;
		}
		if ("skybox".equals(event.getKey()) || "skyboxDirectory".equals(event.getKey()))
		{
			// The next frame resolves the choice against the time of day and reloads.
			requestedSkybox = null;
		}
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
			try
			{
				decoded = SkyboxLoader.load(file);
				if (choice.isBodyless())
				{
					sunAzimuth = Double.NaN;
				}
				else if (twinFile != null)
				{
					SkyboxLoader.Decoded unlit = SkyboxLoader.load(twinFile);
					try
					{
						sunAzimuth = SkyboxLoader.sunByDifference(decoded, unlit);
					}
					finally
					{
						MemoryUtil.memFree(unlit.pixels);
					}
				}
				else
				{
					sunAzimuth = decoded.sunAzimuthDegrees;
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
						skyboxSunAzimuth = sunAzimuth;
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

	// Depth along the camera axis to keep sharp: the local player's chest height, or a fixed distance.
	private float focusDistance()
	{
		if (config.focusMode() == RltxConfig.FocusMode.PLAYER)
		{
			Player player = client.getLocalPlayer();
			if (player != null)
			{
				LocalPoint lp = player.getLocalLocation();
				int height = Perspective.getTileHeight(client, lp, player.getWorldLocation().getPlane()) - Perspective.LOCAL_HALF_TILE_SIZE;
				float dx = lp.getX() - frame.cameraX;
				float dy = height - frame.cameraY;
				float dz = lp.getY() - frame.cameraZ;
				float[] r = frame.forwardRotation;
				float depth = r[6] * dx + r[7] * dy + r[8] * dz;
				return Math.max(depth, Perspective.LOCAL_TILE_SIZE);
			}
		}
		return config.focusDistance() * Perspective.LOCAL_TILE_SIZE;
	}

	// Sun direction from the clock and place, or from the manual settings. Direct light fades
	// through twilight and warms near the horizon; at night a dim moon stands opposite the sun.
	private void fillSun()
	{
		double azimuth;
		double elevation;
		if (config.sunMode() == RltxConfig.SunMode.REAL_TIME)
		{
			long now = System.currentTimeMillis() + config.timeOffset() * 3_600_000L;
			SolarPosition sun = SolarPosition.compute(now, config.latitude(), config.longitude());
			azimuth = sun.azimuthDegrees;
			elevation = sun.elevationDegrees;
		}
		else
		{
			azimuth = config.sunAzimuth();
			elevation = config.sunElevation();
		}

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
		else
		{
			lightAzimuth = azimuth + 180.0;
			lightElevation = -elevation;
			frame.sunIntensity = (float) (config.sunIntensity() / 100.0 * config.moonlight() / 100.0);
			frame.sunR = 0.60f;
			frame.sunG = 0.72f;
			frame.sunB = 1.00f;
		}
		double az = Math.toRadians(lightAzimuth);
		double el = Math.toRadians(lightElevation);
		// Scene space has north along +z, east along +x and up along -y.
		frame.sunX = (float) (Math.sin(az) * Math.cos(el));
		frame.sunY = (float) -Math.sin(el);
		frame.sunZ = (float) (Math.cos(az) * Math.cos(el));

		Skybox.Phase phase = elevation > 8.0 ? Skybox.Phase.DAY
			: elevation > -4.0 ? (azimuth < 180.0 ? Skybox.Phase.SUNRISE : Skybox.Phase.SUNSET)
			: Skybox.Phase.NIGHT;
		sunAzimuthNow = azimuth;
		sunElevationNow = elevation;
		phaseNow = phase;
		Skybox desired = config.skybox().resolve(phase);
		if (desired != requestedSkybox)
		{
			loadSkybox(desired);
		}

		// Turn the sky so its painted sun or moon sits where the light comes from.
		double alignment = Double.isNaN(skyboxSunAzimuth) ? 0.0 : skyboxSunAzimuth - lightAzimuth;
		frame.skyboxRotation = (float) Math.toRadians(config.skyboxRotation() + alignment);
	}

	private Palette palette()
	{
		Palette p = palette;
		if (p == null || p.brightness() != client.getTextureProvider().getBrightness())
		{
			p = new Palette(client.getTextureProvider());
			palette = p;
		}
		return p;
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			return;
		}
		long start = System.nanoTime();
		StaticScene built = StaticSceneBuilder.build(scene, renderCallbackManager, palette());
		log.debug("Built static scene: {} faces, {} groups in {} ms", built.geometry.faces(), built.groupCount(), (System.nanoTime() - start) / 1_000_000);
		pendingStatic = built;
	}

	@Override
	public void swapScene(Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			return;
		}
		StaticScene built = pendingStatic;
		if (built == null)
		{
			return;
		}
		pendingStatic = null;
		renderer.setStaticScene(built);
		staticLoaded = true;
		staticDirty = false;
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			staticDirty = true;
		}
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			return;
		}
		if (staticDirty && staticLoaded)
		{
			staticDirty = false;
			renderer.setStaticScene(StaticSceneBuilder.build(scene, renderCallbackManager, palette()));
		}

		// The client only processes zones within this radius: actors, temporary objects and tile
		// picking all depend on it. Our own rendering covers the whole scene regardless.
		scene.setDrawDistance(config.drawDistance());

		renderer.beginFrame();
		dynamic.clear();
		dynamicTranslucent.clear();

		frame.cameraX = cameraX;
		frame.cameraY = cameraY;
		frame.cameraZ = cameraZ;
		frame.zoom = client.getScale();
		CameraMath.inverseRotation(cameraPitch, cameraYaw, frame.inverseRotation);
		CameraMath.forwardRotation(cameraPitch, cameraYaw, frame.forwardRotation);
		frame.minLevel = minLevel;
		frame.level = level;
		frame.maxLevel = maxLevel;
		frame.hiddenRoofIds = hideRoofIds;
		frameActive = true;
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject,
		Renderable renderable, Model model, int orientation, int x, int y, int z)
	{
		if (!frameActive || scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			countDropped(scene);
			return;
		}
		if (!renderCallbackManager.drawObject(scene, tileObject))
		{
			return;
		}
		++statDynamicCalls;
		int opaqueStart = dynamic.faces();
		int translucentStart = dynamicTranslucent.faces();
		framePusher.push(model, orientation, x, y, z, palette(), dynamic, dynamicTranslucent);
		motion.record(renderable, dynamic, opaqueStart, dynamicTranslucent, translucentStart);
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model model, int orientation, int x, int y, int z)
	{
		if (!frameActive || scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			countDropped(scene);
			return;
		}
		if (!renderCallbackManager.drawObject(scene, gameObject))
		{
			return;
		}
		++statTempCalls;
		int opaqueStart = dynamic.faces();
		int translucentStart = dynamicTranslucent.faces();
		framePusher.push(model, orientation, x, y, z, palette(), dynamic, dynamicTranslucent);
		motion.record(gameObject.getRenderable(), dynamic, opaqueStart, dynamicTranslucent, translucentStart);
	}

	private void countDropped(Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			++statSubScene;
		}
		else
		{
			++statInactive;
		}
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		if (!frameActive || scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			return;
		}
		frameActive = false;
		if (sceneFramePending)
		{
			log.debug("Dropping frame: previous scene image was never presented");
			return;
		}

		int width = client.getViewportWidth();
		int height = client.getViewportHeight();
		if (width <= 0 || height <= 0)
		{
			return;
		}
		if (renderer.ensureOutput(width, height))
		{
			compositor.importSceneImage(renderer.outputFd(), renderer.outputAllocationSize(), width, height);
		}

		fillLighting();
		frame.pattern = false;
		long start = System.nanoTime();
		renderer.submit(frame, dynamic, dynamicTranslucent, glSignalPending);
		motion.endFrame();
		statSubmitNanos += System.nanoTime() - start;
		glSignalPending = false;
		sceneFramePending = true;

		++statFrames;
		if (start - statLastReport > 5_000_000_000L)
		{
			log.debug("frames={} dynamicCalls={} tempCalls={} droppedInactive={} droppedSubScene={} dynamicFaces={} submitAvgMs={} gpuMs={} levels={}/{}/{}",
				statFrames, statDynamicCalls, statTempCalls, statInactive, statSubScene, dynamic.faces() + dynamicTranslucent.faces(),
				String.format("%.2f", statSubmitNanos / 1_000_000.0 / Math.max(statFrames, 1)), String.format("%.2f", renderer.lastGpuMillis()),
				frame.minLevel, frame.level, frame.maxLevel);
			log.debug("sun: mode={} azimuth={} elevation={} phase={} intensity={} skybox={}",
				config.sunMode(), Math.round(sunAzimuthNow), Math.round(sunElevationNow), phaseNow,
				String.format("%.2f", frame.sunIntensity), requestedSkybox);
			Player local = client.getLocalPlayer();
			if (local != null)
			{
				Model localModel = local.getModel();
				log.debug("local player: plane={} clientPlane={} worldView={} model={} faces={}",
					local.getWorldLocation().getPlane(), client.getPlane(), local.getWorldView().getId(),
					localModel != null, localModel != null ? localModel.getFaceCount() : -1);
			}
			else
			{
				log.debug("local player: null");
			}
			statLastReport = start;
			statFrames = 0;
			statDynamicCalls = 0;
			statTempCalls = 0;
			statInactive = 0;
			statSubScene = 0;
			statSubmitNanos = 0;
		}
	}

	private void fillLighting()
	{
		fillSun();
		frame.sunAngularRadius = (float) Math.toRadians(config.sunSize() / 2.0);
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
		frame.antialias = config.antialias();
		frame.water = config.water();
		// Wrapped where every integer scroll speed lands on a whole texture repeat.
		frame.gameCycle = client.getGameCycle() & 0x3FFF;
		frame.waveStrength = config.waveStrength() / 100f * 0.12f;
		frame.aperture = config.aperture();
		frame.focusDistance = focusDistance();
		frame.shutter = config.motionBlur() / 100f;
		frame.skybox = skyboxLoaded;
		float skyIntensity = config.skyIntensity() / 100f;
		Color sky = config.skyColor();
		frame.backgroundR = sky.getRed() / 255f;
		frame.backgroundG = sky.getGreen() / 255f;
		frame.backgroundB = sky.getBlue() / 255f;
		if (skyboxLoaded)
		{
			frame.skyR = frame.skyG = frame.skyB = skyIntensity;
		}
		else
		{
			frame.skyR = frame.backgroundR * skyIntensity;
			frame.skyG = frame.backgroundG * skyIntensity;
			frame.skyB = frame.backgroundB * skyIntensity;
		}
	}

	// The client decodes its textures lazily; once every one is available they are packed into
	// one array and uploaded. Brightness is forced to 1 so the gamma is applied only by us.
	private void ensureGameTextures()
	{
		if (gameTexturesUploaded || !config.textures())
		{
			return;
		}
		TextureProvider provider = client.getTextureProvider();
		if (provider == null)
		{
			return;
		}
		Texture[] textures = provider.getTextures();
		if (textures == null || textures.length == 0)
		{
			return;
		}
		for (int id = 0; id < textures.length; ++id)
		{
			if (textures[id] != null && provider.load(id) == null)
			{
				return;
			}
		}

		final int size = 128;
		ByteBuffer packed = MemoryUtil.memCalloc(textures.length * size * size * 4);
		java.util.BitSet cutouts = new java.util.BitSet();
		try
		{
			double brightness = provider.getBrightness();
			provider.setBrightness(1.0);
			int uploaded = 0;
			for (int id = 0; id < textures.length; ++id)
			{
				if (textures[id] == null)
				{
					continue;
				}
				int[] pixels = provider.load(id);
				if (pixels == null || pixels.length != size * size)
				{
					log.warn("Texture {} has {} pixels; expected {}x{}", id, pixels == null ? 0 : pixels.length, size, size);
					continue;
				}
				int base = id * size * size * 4;
				for (int i = 0; i < pixels.length; ++i)
				{
					int rgb = pixels[i];
					if (rgb == 0)
					{
						cutouts.set(id);
						continue;
					}
					int o = base + i * 4;
					packed.put(o, (byte) (rgb >> 16)).put(o + 1, (byte) (rgb >> 8)).put(o + 2, (byte) rgb).put(o + 3, (byte) 0xff);
				}
				++uploaded;
			}
			provider.setBrightness(brightness);
			float[] scroll = new float[textures.length * 2];
			for (int id = 0; id < textures.length; ++id)
			{
				Texture texture = textures[id];
				if (texture == null)
				{
					continue;
				}
				float speed = texture.getAnimationSpeed();
				switch (texture.getAnimationDirection())
				{
					case 1:
						scroll[id * 2 + 1] = -speed;
						break;
					case 3:
						scroll[id * 2 + 1] = speed;
						break;
					case 2:
						scroll[id * 2] = -speed;
						break;
					case 4:
						scroll[id * 2] = speed;
						break;
					default:
						break;
				}
			}
			renderer.setTextureAnimation(scroll);
			renderer.setTextureArray(textures.length, size, packed);
			gameTexturesUploaded = true;
			// Faces with cutout textures need the non-opaque path; reclassify the static scene.
			TextureCutouts.set(cutouts);
			staticDirty = true;
			log.info("Uploaded {} game textures, {} with cutouts", uploaded, cutouts.cardinality());
		}
		finally
		{
			MemoryUtil.memFree(packed);
		}
	}

	@Override
	public void draw(int overlayColor)
	{
		GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING || !glReady)
		{
			return;
		}
		if (gameState == GameState.LOGGED_IN)
		{
			ensureGameTextures();
		}

		int canvasWidth = client.getCanvasWidth();
		int canvasHeight = client.getCanvasHeight();
		BufferProvider bufferProvider = client.getBufferProvider();
		compositor.updateUiTexture(bufferProvider.getPixels(), bufferProvider.getWidth(), bufferProvider.getHeight(), canvasWidth, canvasHeight);

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glClearColor(0, 0, 0, 1);
		glClear(GL_COLOR_BUFFER_BIT);

		AffineTransform dpi = clientUI.getGraphicsConfiguration().getDefaultTransform();
		boolean stretched = client.isStretchedEnabled();
		Dimension stretchedDim = stretched ? client.getStretchedDimensions() : null;
		int targetWidth = stretched ? stretchedDim.width : canvasWidth;
		int targetHeight = stretched ? stretchedDim.height : canvasHeight;

		if (sceneFramePending)
		{
			drawSceneQuad(dpi, canvasWidth, canvasHeight, stretched, stretchedDim);
			sceneFramePending = false;
			glSignalPending = true;
		}
		else if (config.loginPattern() && gameState != GameState.LOGGED_IN && gameState != GameState.LOADING)
		{
			renderer.beginFrame();
			if (renderer.ensureOutput(canvasWidth, canvasHeight))
			{
				compositor.importSceneImage(renderer.outputFd(), renderer.outputAllocationSize(), canvasWidth, canvasHeight);
			}
			frame.pattern = true;
			renderer.submit(frame, empty, empty, glSignalPending);
			glSignalPending = false;
			compositor.drawScene(0, 0, scaled(dpi.getScaleX(), targetWidth), scaled(dpi.getScaleY(), targetHeight));
			glSignalPending = true;
			if (!patternSampled)
			{
				patternSampled = true;
				int texel = compositor.readSceneTexel(canvasWidth / 2, canvasHeight / 2, canvasWidth, canvasHeight);
				log.info("Login pattern centre texel (expect roughly r=g=0x7f or 0x4c): 0x{}", Integer.toHexString(texel));
			}
		}

		compositor.drawUi(overlayColor, 0, 0, scaled(dpi.getScaleX(), targetWidth), scaled(dpi.getScaleY(), targetHeight));

		try
		{
			awtContext.swapBuffers();
		}
		catch (RuntimeException ex)
		{
			if (!canvas.isValid())
			{
				return;
			}
			log.error("error swapping buffers", ex);
			SwingUtilities.invokeLater(() ->
			{
				try
				{
					pluginManager.stopPlugin(this);
				}
				catch (PluginInstantiationException ex2)
				{
					log.error("error stopping plugin", ex2);
				}
			});
			return;
		}
		GlCompositor.checkErrors("frame");
	}

	// Viewport placement follows the GPU plugin, including its one pixel of
	// padding when the canvas is stretched.
	private void drawSceneQuad(AffineTransform dpi, int canvasWidth, int canvasHeight, boolean stretched, Dimension stretchedDim)
	{
		int viewportWidth = client.getViewportWidth();
		int viewportHeight = client.getViewportHeight();
		int xOffset = client.getViewportXOffset();
		int yOffset = client.getViewportYOffset();
		int targetCanvasHeight = canvasHeight;
		if (stretched)
		{
			double scaleX = stretchedDim.getWidth() / canvasWidth;
			double scaleY = stretchedDim.getHeight() / canvasHeight;
			targetCanvasHeight = stretchedDim.height;
			viewportWidth = (int) Math.ceil(scaleX * viewportWidth) + 2;
			viewportHeight = (int) Math.ceil(scaleY * viewportHeight) + 2;
			xOffset = (int) Math.floor(scaleX * xOffset) - 1;
			yOffset = (int) Math.floor(scaleY * yOffset) - 1;
		}
		compositor.drawScene(
			scaled(dpi.getScaleX(), xOffset),
			scaled(dpi.getScaleY(), targetCanvasHeight - viewportHeight - yOffset),
			scaled(dpi.getScaleX(), viewportWidth),
			scaled(dpi.getScaleY(), viewportHeight));
	}

	private static int scaled(double scale, int value)
	{
		return (int) Math.round(value * scale);
	}
}
