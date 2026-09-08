package rltx;

import static org.lwjgl.opengl.GL43C.*;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.BufferProvider;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.FloatProjection;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Animation;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.rlawt.AWTContext;
import okhttp3.OkHttpClient;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryUtil;
import rltx.gl.GlCompositor;
import rltx.scene.GeometryBuffer;
import rltx.scene.GroundTextures;
import rltx.scene.Materials;
import rltx.scene.ModelPusher;
import rltx.scene.MotionHistory;
import rltx.scene.Palette;
import rltx.scene.StaticScene;
import rltx.scene.StaticSceneBuilder;
import rltx.scene.TextureCutouts;
import rltx.scene.TextureUpscaler;
import rltx.scene.WaterSim;
import rltx.scene.lights.SceneLights;
import rltx.vk.FrameParams;
import rltx.vk.Ngx;
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

	@Inject
	private ConfigManager configManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private OverlayManager overlayManager;
	@Inject
	private EventBus eventBus;

	@Inject
	private NpcOverlayService npcOverlayService;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	// The features, each in its own class, wired together here; built at start once the
	// injected services exist.
	private PhotoMode photo;
	private FreeCamera freeCamera;
	private Cinema cinema;
	private Environment environment;
	private LocalLights lights;
	private Foliage foliage;
	private Waves waves;
	private Ground ground;
	private Chrome chrome;
	private StyledMenu styledMenu;
	private Outfits outfits;
	private Lyku lyku;
	private LykuOrg lykuOrg;
	private final GeometryBuffer portraitOpaque = new GeometryBuffer(1 << 12);
	private final GeometryBuffer portraitTranslucent = new GeometryBuffer(1 << 10);
	private final GeometryBuffer portraitWater = new GeometryBuffer(1 << 8);
	// The offscreen avatar renders from its own frame + buffers so nothing it does
	// touches the live frame or the live geometry. Guarded by avatarBusy: only one
	// avatar render at a time.
	private final FrameParams avatarFrame = new FrameParams();
	private volatile boolean avatarBusy;
	private Stages stages;
	private static final int GPU_FLAGS = DrawCallbacks.GPU | DrawCallbacks.ZBUF | DrawCallbacks.NORMALS | DrawCallbacks.RENDER_THREADS(0);
	/** The stem of a photo just saved whose stock render is still to be drawn, or null. */
	private String stockStem;
	private PluginGlow glow;
	private Footprints footprints;
	private Ripples ripples;
	private final Cells cells = new Cells();

	private ControlPanel controlPanel;
	private Presets presets;
	private AreaRules areaRules;
	private Showcase showcase;
	private volatile WorldPoint currentPosition;

	private final HotkeyListener controlPanelKey = new HotkeyListener(() -> config.controlPanelKey())
	{
		@Override
		public void hotkeyPressed()
		{
			controlPanel.toggle();
		}
	};

	private final HotkeyListener showcaseKey = new HotkeyListener(() -> config.showcaseKey())
	{
		@Override
		public void hotkeyPressed()
		{
			configManager.setConfiguration(RltxConfig.GROUP, "showcase", !config.showcase());
		}
	};

	private void say(String message)
	{
		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
	}

	private boolean runoffUploaded;

	// Steps the runoff simulation and uploads it while any water lies on the ground, plus one
	// final upload of the dry state so the shader stops seeing stale water.
	private void fillRunoff()
	{
		LoadedScene top = scenes.get(WorldView.TOPLEVEL);
		frame.runoff = config.runoff() && top != null && top.water != null;
		if (!frame.runoff)
		{
			return;
		}
		boolean wet = top.water.step(environment.weatherDt, environment.weatherNow.rain);
		if (wet || !runoffUploaded)
		{
			renderer.setRunoff(top.water.packed());
			runoffUploaded = !wet;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		chrome.apply();
		outfits.tick();
		Player local = client.getLocalPlayer();
		currentPosition = local == null ? null : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		stages.tick(scenes.get(WorldView.TOPLEVEL), currentPosition);
		String area = areaRules.tick(currentPosition, config.areaSettings(), onMistyGround(currentPosition));
		if (area != null)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", area, null);
		}
		glow.tick();
		footprints.retainPresent(client.getTopLevelWorldView());
		ripples.retainPresent(client.getTopLevelWorldView());
		// The date moving the season along recolours the static scene.
		Palette current = palette;
		if (current != null && (current.season != environment.seasonKind() || current.seasonProgress != environment.seasonProgress()))
		{
			staticDirty = true;
		}
	}

	// Whether the ground under a world position lies in the mist grid's coverage.
	private boolean onMistyGround(WorldPoint p)
	{
		LoadedScene top = scenes.get(WorldView.TOPLEVEL);
		WorldView wv = client.getTopLevelWorldView();
		if (p == null || top == null || top.mist == null || wv == null)
		{
			return false;
		}
		LocalPoint lp = LocalPoint.fromWorld(wv, p);
		if (lp == null)
		{
			return false;
		}
		int size = top.scene.getExtendedTiles()[0].length;
		int offset = (size - Constants.SCENE_SIZE) / 2;
		int vx = lp.getSceneX() + offset;
		int vy = lp.getSceneY() + offset;
		if (vx < 0 || vy < 0 || vx > size || vy > size)
		{
			return false;
		}
		return top.mist[(vx * (size + 1) + vy) * StaticSceneBuilder.MIST_FLOATS + 1] > 0.25f;
	}

	// Whether the camera has gone below a water surface, and where that surface is.
	private void fillUnderwater()
	{
		frame.underwater = false;
		LoadedScene top = scenes.get(WorldView.TOPLEVEL);
		WorldView wv = client.getTopLevelWorldView();
		if (!config.underwater() || top == null || wv == null)
		{
			return;
		}
		LocalPoint lp = new LocalPoint((int) frame.cameraX, (int) frame.cameraZ, wv);
		if (!lp.isInScene())
		{
			return;
		}
		int plane = client.getPlane();
		if (!top.waterBed.isWater(plane, lp.getSceneX(), lp.getSceneY()))
		{
			return;
		}
		float surface = Perspective.getTileHeight(client, lp, plane);
		if (frame.cameraY > surface + 4f)
		{
			frame.underwater = true;
			frame.waterSurfaceY = surface;
		}
	}

	// Smoke sources: the fires drawn this frame, joined by the scene's chimneys at upload.
	private static final int MAX_PLUME_SOURCES = 64;
	private final float[] plumeSources = new float[MAX_PLUME_SOURCES * 4];
	private int plumeSourceCount;
	private final float[] plumePacked = new float[RtRenderer.MAX_PLUMES * 4];

	// Uploads the nearest trees while the season has them shedding.
	private final float[] treePacked = new float[RtRenderer.MAX_TREES * 4];

	private void fillTrees()
	{
		LoadedScene top = scenes.get(WorldView.TOPLEVEL);
		float[] all = top == null ? null : top.built.trees;
		if (all == null || all.length == 0 || (frame.leafFall <= 0f && frame.petals <= 0f))
		{
			frame.treeCount = 0;
			return;
		}
		int total = all.length / 4;
		float[] distance = new float[total];
		int[] order = new int[total];
		for (int i = 0; i < total; ++i)
		{
			float dx = all[i * 4] - frame.cameraX, dz = all[i * 4 + 2] - frame.cameraZ;
			distance[i] = dx * dx + dz * dz;
			order[i] = i;
		}
		int keep = Math.min(total, RtRenderer.MAX_TREES);
		for (int i = 0; i < keep; ++i)
		{
			int best = i;
			for (int j = i + 1; j < total; ++j)
			{
				if (distance[order[j]] < distance[order[best]])
				{
					best = j;
				}
			}
			int swap = order[i];
			order[i] = order[best];
			order[best] = swap;
			System.arraycopy(all, order[i] * 4, treePacked, i * 4, 4);
		}
		renderer.setTrees(treePacked, keep * 4);
		frame.treeCount = keep;
	}

	// Uploads the nearest smoke sources, since only so many plumes are marched per pixel.
	private void fillPlumes()
	{
		LoadedScene top = scenes.get(WorldView.TOPLEVEL);
		boolean smoke = config.smoke() && top != null;
		float[] fixed = smoke ? top.built.plumes : new float[0];
		int moving = smoke ? plumeSourceCount : 0;
		plumeSourceCount = 0;
		int total = fixed.length / 4 + moving;
		if (total == 0)
		{
			frame.plumeCount = 0;
			return;
		}
		float[] distance = new float[total];
		int[] order = new int[total];
		for (int i = 0; i < total; ++i)
		{
			float[] from = i < moving ? plumeSources : fixed;
			int o = (i < moving ? i : i - moving) * 4;
			float dx = from[o] - frame.cameraX, dy = from[o + 1] - frame.cameraY, dz = from[o + 2] - frame.cameraZ;
			distance[i] = dx * dx + dy * dy + dz * dz;
			order[i] = i;
		}
		int keep = Math.min(total, RtRenderer.MAX_PLUMES);
		for (int i = 0; i < keep; ++i)
		{
			int best = i;
			for (int j = i + 1; j < total; ++j)
			{
				if (distance[order[j]] < distance[order[best]])
				{
					best = j;
				}
			}
			int swap = order[i];
			order[i] = order[best];
			order[best] = swap;
			int source = order[i];
			float[] from = source < moving ? plumeSources : fixed;
			System.arraycopy(from, (source < moving ? source : source - moving) * 4, plumePacked, i * 4, 4);
		}
		renderer.setPlumes(plumePacked, keep * 4);
		frame.plumeCount = keep;
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		lights.beforeRender();
		glow.fillHighlights();
	}

	private Canvas canvas;
	private AWTContext awtContext;
	private boolean glReady;
	private GlCompositor compositor;
	private VkContext vk;
	private RtRenderer renderer;
	// A second, independent renderer for the offscreen avatar, present only when the
	// GPU gave us a dedicated queue. It shares nothing mutable with the live renderer,
	// reads back via Vulkan (no GL compositor), and runs off the client thread.
	private RtRenderer avatarRenderer;

	private volatile Palette palette;
	private boolean gameTexturesUploaded;

	// Scenes are keyed by world view id; nested world views (boats, the top-level scene's
	// moving sub-scenes) each carry their placement matrix for the frame.
	private final Map<Integer, LoadedScene> pendingScenes = new ConcurrentHashMap<>();
	private final Map<Integer, LoadedScene> scenes = new HashMap<>();
	private final Map<Integer, float[]> subTransforms = new HashMap<>();
	private final Set<Long> dirtyZones = new LinkedHashSet<>();
	private boolean staticDirty;
	// Actors the client did not draw this frame still cast shadows and bounce light; only those
	// this close to the player are worth animating for it.
	private static final int OFFSCREEN_ACTOR_RANGE = 24 * Perspective.LOCAL_TILE_SIZE;

	private final GeometryBuffer dynamic = new GeometryBuffer(1 << 16);
	private final GeometryBuffer dynamicTranslucent = new GeometryBuffer(1 << 12);
	private final GeometryBuffer dynamicWater = new GeometryBuffer(1 << 14);
	private final GeometryBuffer empty = new GeometryBuffer(1);
	private final ModelPusher framePusher = new ModelPusher();
	private final MotionHistory motion = new MotionHistory();
	private final FrameParams frame = new FrameParams();

	private boolean frameActive;
	private boolean sceneFramePending;
	private boolean glSignalPending;
	private boolean patternSampled;

	private int statDynamicCalls, statTempCalls, statFrames, statInactive, statSubScene, statOffscreen;
	private long statSubmitNanos, statLastReport, statInfoReport;
	// For the 30-second line: frames, our client-thread time per frame, and how much of it was
	// spent waiting for the GPU to finish the frame before.
	private int statInfoFrames;
	private long statCpuNanos, statWaitBase, frameCpuStart;

	@Provides
	RltxConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RltxConfig.class);
	}

	@Override
	protected void startUp()
	{
		presets = new Presets(configManager, config, gson);
		showcase = new Showcase(presets, configManager, gson);
		showcase.recover();
		areaRules = new AreaRules(presets, gson);
		try
		{
			areaRules.load();
		}
		catch (IOException e)
		{
			log.warn("Area settings not loaded from {}", AreaRules.FILE, e);
		}
		photo = new PhotoMode(client, config, configManager, drawManager, this::say);
		photo.onSaved(stem ->
		{
			if (config.photoStock())
			{
				clientThread.invoke(() -> stockStem = stem);
			}
		});
		freeCamera = new FreeCamera(client, config);
		cinema = new Cinema(config, freeCamera, photo, drawManager, gson, this::say);
		environment = new Environment(client, clientThread, config, configManager, okHttpClient, gson, cinema, frame);
		lights = new LocalLights(client, config, gson, frame);
		foliage = new Foliage(client, clientThread, config, lights, frame);
		waves = new Waves(config, frame);
		ground = new Ground(config, frame);
		glow = new PluginGlow(client, clientThread, config, pluginManager, overlayManager, npcOverlayService, frame);
		footprints = new Footprints(client, config, frame);
		ripples = new Ripples(client, config, frame);
		chrome = new Chrome(client, config);
		styledMenu = new StyledMenu(client, config);
		outfits = new Outfits(client, config, this::portraitOrAvatar);
		lyku = new Lyku(okHttpClient, gson, configManager, this::say);
		lyku.onConnected(() -> clientThread.invoke(() ->
		{
			if (config.lykuAvatar())
			{
				pushOutfitToLyku();
			}
		}));
		lykuOrg = new LykuOrg(okHttpClient, gson, configManager, config, this::say);
		lykuOrg.onConnected(() -> clientThread.invoke(() ->
		{
			if (config.lykuOrgAvatar())
			{
				pushOutfitToLyku();
			}
		}));
		stages = new Stages(client, this::say);
		eventBus.register(styledMenu);
		overlayManager.add(styledMenu);
		controlPanel = new ControlPanel(configManager, config, presets, areaRules, () -> currentPosition, glow::previewPolygons, cinema.control, cinema.paths);
		keyManager.registerKeyListener(controlPanelKey);
		keyManager.registerKeyListener(showcaseKey);
		photo.register(keyManager, mouseManager);
		freeCamera.register(keyManager, mouseManager);
		cinema.register(keyManager);
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
				Ngx.load(System.getProperty("rltx.nativeDir"), new File(RuneLite.RUNELITE_DIR, "rltx/ngx").getPath());
				if (!Ngx.loaded())
				{
					log.info("DLSS unavailable: {}", Ngx.unavailableReason());
				}
				vk = VkContext.create(compositor.deviceUuid());
				renderer = new RtRenderer(vk);
				environment.attach(renderer);
				float[] materials = Materials.table(gson);
				GroundTextures.applyMaterials(materials);
				renderer.setMaterials(materials);
				compositor.importSemaphores(renderer.semaphoreVkDoneHandle(), renderer.semaphoreGlDoneHandle());
				// The avatar renderer needs materials to shade, but no GL interop (it reads
				// back to CPU) and no Environment (it drives its own FrameParams).
				if (vk.avatarQueueDedicated)
				{
					avatarRenderer = new RtRenderer(vk, vk.avatarQueue, vk.avatarCommandPool);
					avatarRenderer.setMaterials(materials);
				}

				client.setDrawCallbacks(this);
				// UNLIT_FACE_COLORS is deliberately absent: with it set from client start, actors stop
				// being handed to drawDynamic. Face colours fall back to the client's lit colours.
				client.setGpuFlags(GPU_FLAGS);
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
		keyManager.unregisterKeyListener(controlPanelKey);
		keyManager.unregisterKeyListener(showcaseKey);
		controlPanel.dispose();
		areaRules.reset();
		showcase.set(false);
		cinema.shutDown();
		cinema.unregister(keyManager);
		photo.unregister(keyManager, mouseManager);
		freeCamera.unregister(keyManager, mouseManager);
		lights.reset();
		glow.clear();
		eventBus.unregister(styledMenu);
		overlayManager.remove(styledMenu);
		clientThread.invoke(() ->
		{
			chrome.remove();
			lights.restoreWeapon();
			glow.restoreOverlays();
			client.setGpuFlags(0);
			client.setDrawCallbacks(null);

			if (avatarRenderer != null)
			{
				avatarRenderer.destroy();
				avatarRenderer = null;
			}
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
			pendingScenes.clear();
			scenes.clear();
			subTransforms.clear();
			dirtyZones.clear();
			staticDirty = false;
			frameActive = false;
			sceneFramePending = false;
			glSignalPending = false;
			// Everything the new renderer must be given again on the next start.
			environment.detach();
			gameTexturesUploaded = false;
			patternSampled = false;
			runoffUploaded = false;
			palette = null;

			// Restores the interface buffer without an alpha channel.
			client.resizeCanvas();
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!RltxConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (controlPanel != null)
		{
			controlPanel.refresh(event.getKey());
		}
		if ("showcase".equals(event.getKey()))
		{
			showcase.set(config.showcase());
			say(config.showcase() ? "RLTX: showcase on" : "RLTX: showcase off, your settings are back");
		}
		if ("lykuAvatar".equals(event.getKey()) && config.lykuAvatar() || "lykuOrgAvatar".equals(event.getKey()) && config.lykuOrgAvatar())
		{
			// Turning it on puts the outfit worn now up straight away, not only the next change.
			clientThread.invoke(this::pushOutfitToLyku);
		}
		if ("lykuConnect".equals(event.getKey()) && config.lykuConnect())
		{
			// A tick box standing in for a button: it opens the browser and clears itself.
			configManager.setConfiguration(RltxConfig.GROUP, "lykuConnect", false);
			lyku.connect();
		}
		if ("lykuOrgConnect".equals(event.getKey()) && config.lykuOrgConnect())
		{
			configManager.setConfiguration(RltxConfig.GROUP, "lykuOrgConnect", false);
			lykuOrg.connect();
		}
		if ("heldTorch".equals(event.getKey()) && !config.heldTorch())
		{
			clientThread.invoke(lights::restoreWeapon);
		}
		if ("pathGlow".equals(event.getKey()) && !config.pathGlow())
		{
			glow.disablePath();
		}
		if ("markerGlow".equals(event.getKey()) && !config.markerGlow())
		{
			glow.disableMarkers();
		}
		if (renderer == null)
		{
			return;
		}
		if ("skybox".equals(event.getKey()) || "skyboxDirectory".equals(event.getKey()))
		{
			environment.reloadSkybox();
		}
		if ("unlitColours".equals(event.getKey()) || "treeScale".equals(event.getKey()) || "seasonMode".equals(event.getKey()))
		{
			staticDirty = true;
		}
	}

	private static int dlssQuality(RltxConfig.DlssMode mode)
	{
		switch (mode)
		{
			case QUALITY:
				return Ngx.QUALITY_QUALITY;
			case BALANCED:
				return Ngx.QUALITY_BALANCED;
			case PERFORMANCE:
				return Ngx.QUALITY_PERFORMANCE;
			case ULTRA_PERFORMANCE:
				return Ngx.QUALITY_ULTRA_PERFORMANCE;
			case DLAA:
				return Ngx.QUALITY_DLAA;
			default:
				return -1;
		}
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

	private Palette palette()
	{
		Palette p = palette;
		boolean undo = config.unlitColours();
		int season = environment.seasonKind();
		float progress = environment.seasonProgress();
		if (p == null || p.brightness() != client.getTextureProvider().getBrightness() || p.undoShading != undo || p.season != season || p.seasonProgress != progress)
		{
			p = new Palette(client.getTextureProvider(), undo, season, progress);
			palette = p;
		}
		return p;
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		long start = System.nanoTime();
		Palette p = palette();
		foliage.classify(StaticSceneBuilder.gameObjectIds(scene));
		StaticScene built = StaticSceneBuilder.build(scene, renderCallbackManager, p, foliage::kind, config.treeScale() / 100f, lights::hasLight);
		log.debug("Built static scene {}: {} faces in {} ms", scene.getWorldViewId(), built.totalFaces(), (System.nanoTime() - start) / 1_000_000);
		SceneLights sceneLights = null;
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			sceneLights = new SceneLights(RtRenderer.MAX_LIGHTS);
			sceneLights.collect(scene, lights.library());
		}
		pendingScenes.put(scene.getWorldViewId(), new LoadedScene(scene, built, StaticSceneBuilder.terrainLight(scene, p), StaticSceneBuilder.waterBed(scene), sceneLights));
	}

	@Override
	public void swapScene(Scene scene)
	{
		int id = scene.getWorldViewId();
		LoadedScene loaded = pendingScenes.remove(id);
		if (loaded == null)
		{
			return;
		}
		renderer.setStaticSet(id, loaded.built, subTransforms.get(id));
		if (id == WorldView.TOPLEVEL)
		{
			loaded.mist = StaticSceneBuilder.mistGrid(scene, foliage::isMisty);
			renderer.setMistGrid(loaded.mist);
			int[][][] heights = scene.getTileHeights();
			int side = heights[0].length;
			float[] flat = new float[heights.length * side * side];
			for (int plane = 0; plane < heights.length; ++plane)
			{
				for (int x = 0; x < side; ++x)
				{
					for (int y = 0; y < side; ++y)
					{
						flat[(plane * side + x) * side + y] = heights[plane][x][y];
					}
				}
			}
			renderer.setTerrainHeights(flat);
			// Water tiles, a bit each over the extended tiles, for the ripple simulation's shores.
			int size = scene.getExtendedTiles()[0].length;
			int offset = (size - Constants.SCENE_SIZE) / 2;
			int[] waterBits = new int[(size * size + 31) / 32];
			for (int plane = 0; plane < 4; ++plane)
			{
				for (int x = 0; x < size; ++x)
				{
					for (int y = 0; y < size; ++y)
					{
						if (loaded.waterBed.isWater(plane, x - offset, y - offset))
						{
							int bit = x * size + y;
							waterBits[bit >> 5] |= 1 << (bit & 31);
						}
					}
				}
			}
			renderer.setWaterMask(waterBits);
			loaded.water = new WaterSim(heights[0]);
			runoffUploaded = false;
			frame.mistGridSize = scene.getExtendedTiles()[0].length + 1;
			frame.mistGridOffset = (scene.getExtendedTiles()[0].length - Constants.SCENE_SIZE) / 2;
		}
		scenes.put(id, loaded);
		dirtyZones.removeIf(key -> (int) (key >> 32) == id);
		if (id == WorldView.TOPLEVEL)
		{
			staticDirty = false;
		}
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		int id = worldView.getId();
		renderer.removeStaticSet(id);
		pendingScenes.remove(id);
		scenes.remove(id);
		subTransforms.remove(id);
		dirtyZones.removeIf(key -> (int) (key >> 32) == id);
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		dirtyZones.add((long) scene.getWorldViewId() << 32 | (long) zx << 16 | zz);
	}

	private void rebuildStatic()
	{
		if (staticDirty)
		{
			staticDirty = false;
			dirtyZones.clear();
			Palette p = palette();
			for (Map.Entry<Integer, LoadedScene> e : scenes.entrySet())
			{
				LoadedScene loaded = e.getValue();
				loaded.terrainLight = StaticSceneBuilder.terrainLight(loaded.scene, p);
				loaded.built = StaticSceneBuilder.build(loaded.scene, renderCallbackManager, p, foliage::kind, config.treeScale() / 100f, lights::hasLight);
				renderer.setStaticSet(e.getKey(), loaded.built, subTransforms.get(e.getKey()));
			}
			return;
		}
		for (long key : dirtyZones)
		{
			int id = (int) (key >> 32);
			int zx = (int) (key >> 16) & 0xffff;
			int zz = (int) key & 0xffff;
			LoadedScene loaded = scenes.get(id);
			if (loaded == null || zx >= loaded.built.zonesX || zz >= loaded.built.zonesZ)
			{
				continue;
			}
			StaticScene.Zone zone = StaticSceneBuilder.buildZone(loaded.scene, zx, zz, renderCallbackManager, palette(), loaded.terrainLight, loaded.waterBed, foliage::kind, config.treeScale() / 100f, lights::hasLight);
			if (!renderer.updateZone(id, zx, zz, zone))
			{
				loaded.built = StaticSceneBuilder.build(loaded.scene, renderCallbackManager, palette(), foliage::kind, config.treeScale() / 100f, lights::hasLight);
				renderer.setStaticSet(id, loaded.built, subTransforms.get(id));
			}
			else
			{
				loaded.built.zones[zx * loaded.built.zonesZ + zz] = zone;
			}
		}
		dirtyZones.clear();
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		int id = scene.getWorldViewId();
		if (id != WorldView.TOPLEVEL)
		{
			// The entity projection is a column-major 4x4; the instance transform wants the top
			// three rows.
			float[] m = ((FloatProjection) entityProjection).getProjection();
			float[] rows = subTransforms.computeIfAbsent(id, k -> new float[12]);
			for (int r = 0; r < 3; ++r)
			{
				for (int c = 0; c < 4; ++c)
				{
					rows[r * 4 + c] = m[c * 4 + r];
				}
			}
			renderer.setStaticView(id, rows, minLevel, level, maxLevel, hideRoofIds);
			return;
		}
		rebuildStatic();

		// The client only processes zones within this radius: actors, temporary objects and tile
		// picking all depend on it. Our own rendering covers the whole scene regardless.
		scene.setDrawDistance(config.drawDistance());

		frameCpuStart = System.nanoTime();
		dynamic.clear();
		dynamicTranslucent.clear();
		dynamicWater.clear();

		freeCamera.setClientCamera(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		if (freeCamera.on)
		{
			if (cinema.active())
			{
				cinema.pose();
			}
			else
			{
				freeCamera.fly();
			}
			cameraX = freeCamera.x;
			cameraY = freeCamera.y;
			cameraZ = freeCamera.z;
			cameraPitch = freeCamera.pitch;
			cameraYaw = freeCamera.yaw;
		}
		frame.cameraX = cameraX;
		frame.cameraY = cameraY;
		frame.cameraZ = cameraZ;
		frame.zoom = client.getScale();
		CameraMath.inverseRotation(cameraPitch, cameraYaw, frame.inverseRotation);
		CameraMath.forwardRotation(cameraPitch, cameraYaw, frame.forwardRotation);
		if (config.photoTiltEnabled() && config.photoTilt() != 0 && !freeCamera.on)
		{
			// The rendered camera swings about the focus point to a lower pitch than the client
			// allows; the client's own camera, and so its picking, is untouched.
			float distance = focusDistance();
			float[] inv = frame.inverseRotation;
			float focusX = cameraX + inv[2] * distance;
			float focusY = cameraY + inv[5] * distance;
			float focusZ = cameraZ + inv[8] * distance;
			float pitch = cameraPitch - (float) Math.toRadians(config.photoTilt());
			CameraMath.inverseRotation(pitch, cameraYaw, frame.inverseRotation);
			CameraMath.forwardRotation(pitch, cameraYaw, frame.forwardRotation);
			frame.cameraX = focusX - inv[2] * distance;
			frame.cameraY = focusY - inv[5] * distance;
			frame.cameraZ = focusZ - inv[8] * distance;
		}
		renderer.setStaticView(id, null, minLevel, level, maxLevel, hideRoofIds);
		frameActive = true;
	}

	// Returns the placement of a scene's local space, or null for the top level; sub-scene draws
	// arriving before their placement is known are dropped for this frame.
	private float[] sceneTransform(Scene scene)
	{
		return scene.getWorldViewId() == WorldView.TOPLEVEL ? null : subTransforms.get(scene.getWorldViewId());
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject,
		Renderable renderable, Model model, int orientation, int x, int y, int z)
	{
		if (!frameActive)
		{
			++statInactive;
			return;
		}
		float[] transform = sceneTransform(scene);
		if (transform == null && scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			++statSubScene;
			return;
		}
		if (!renderCallbackManager.drawObject(scene, tileObject))
		{
			return;
		}
		++statDynamicCalls;
		int opaqueStart = dynamic.faces();
		int translucentStart = dynamicTranslucent.faces();
		boolean torch = lights.torchCarried && renderable == client.getLocalPlayer();
		framePusher.flames = torch || lights.lit(tileObject, renderable);
		framePusher.highlight = renderable instanceof NPC ? glow.highlight((NPC) renderable) : 0;
		framePusher.actor = renderable instanceof Actor;
		framePusher.push(model, orientation, x, y, z, transform, palette(), dynamic, dynamicTranslucent);
		framePusher.flames = false;
		framePusher.actor = false;
		framePusher.highlight = 0;
		if (torch)
		{
			lights.torchDrawn(framePusher);
		}
		if (tileObject != null && framePusher.flameFaces > 0 && plumeSourceCount < MAX_PLUME_SOURCES && foliage.kind(tileObject.getId()) == 4)
		{
			int o = plumeSourceCount++ * 4;
			plumeSources[o] = framePusher.flameX;
			plumeSources[o + 1] = framePusher.flameTop;
			plumeSources[o + 2] = framePusher.flameZ;
			plumeSources[o + 3] = 1f;
		}
		motion.record(renderable, dynamic, opaqueStart, dynamicTranslucent, translucentStart);
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model model, int orientation, int x, int y, int z)
	{
		if (!frameActive)
		{
			++statInactive;
			return;
		}
		float[] transform = sceneTransform(scene);
		if (transform == null && scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			++statSubScene;
			return;
		}
		if (!renderCallbackManager.drawObject(scene, gameObject))
		{
			return;
		}
		++statTempCalls;
		int opaqueStart = dynamic.faces();
		int translucentStart = dynamicTranslucent.faces();
		framePusher.push(model, orientation, x, y, z, transform, palette(), dynamic, dynamicTranslucent);
		motion.record(gameObject.getRenderable(), dynamic, opaqueStart, dynamicTranslucent, translucentStart);
	}

	private void addOffscreenActors()
	{
		WorldView wv = client.getTopLevelWorldView();
		Player local = client.getLocalPlayer();
		if (wv == null || local == null)
		{
			return;
		}
		LocalPoint centre = local.getLocalLocation();
		if (centre == null)
		{
			return;
		}
		for (NPC npc : wv.npcs())
		{
			pushOffscreenActor(npc, centre);
		}
		for (Player player : wv.players())
		{
			pushOffscreenActor(player, centre);
		}
	}

	private void pushOffscreenActor(Actor actor, LocalPoint centre)
	{
		if (actor == null || motion.seen(actor))
		{
			return;
		}
		LocalPoint lp = actor.getLocalLocation();
		if (lp == null || lp.distanceTo(centre) > OFFSCREEN_ACTOR_RANGE)
		{
			return;
		}
		Model model = actor.getModel();
		if (model == null)
		{
			return;
		}
		int plane = actor.getWorldLocation().getPlane();
		int height = Perspective.getTileHeight(client, lp, plane) - actor.getAnimationHeightOffset();
		int opaqueStart = dynamic.faces();
		int translucentStart = dynamicTranslucent.faces();
		framePusher.actor = true;
		framePusher.push(model, actor.getCurrentOrientation(), lp.getX(), height, lp.getY(), null, palette(), dynamic, dynamicTranslucent);
		framePusher.actor = false;
		motion.record(actor, dynamic, opaqueStart, dynamicTranslucent, translucentStart);
		++statOffscreen;
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
		// Photos and cinema frames are traced at full size whatever the render scale or DLSS say.
		boolean held = cinema.active() && !cinema.preview() || photo.burstPending();
		float scale = held ? 1f : config.renderScale() / 100f;
		int dlss = held ? -1 : dlssQuality(config.dlss());
		boolean rr = !held && config.rayReconstruction();
		if (renderer.ensureOutput(width, height, scale, dlss, rr))
		{
			compositor.importSceneImage(renderer.outputHandle(), renderer.outputAllocationSize(), width, height);
		}

		if (photo.focusProbePending())
		{
			photo.probeFocus(renderer);
		}
		environment.fill();
		frame.aperture = config.aperture();
		frame.focusDistance = focusDistance();
		if (cinema.active())
		{
			frame.timeSeconds = cinema.seconds();
		}
		frame.pattern = false;
		long start = System.nanoTime();
		addOffscreenActors();
		LoadedScene top = scenes.get(WorldView.TOPLEVEL);
		int actorFaces = dynamic.faces() + dynamicTranslucent.faces();
		foliage.push(top, dynamic, renderer, environment.weatherNow);
		int foliageFaces = dynamic.faces() + dynamicTranslucent.faces() - actorFaces;
		ground.push(top, dynamic, renderer, gameTexturesUploaded);
		int groundFaces = dynamic.faces() + dynamicTranslucent.faces() - actorFaces - foliageFaces;
		waves.push(top, dynamicWater, renderer);
		ripples.fill(renderer, top, environment.weatherDt);
		lights.fill(renderer, top == null ? null : top.lights);
		glow.fillGuide(renderer, cells);
		glow.fillMarkers(renderer, cells);
		fillPlumes();
		fillTrees();
		footprints.track(renderer, cells);
		renderer.setCells(cells.bits);
		frame.textureDisplacement = config.textureDisplacement();
		frame.sheetChrome = config.chrome() == RltxConfig.Chrome.GLASS ? 1 : config.chrome() == RltxConfig.Chrome.PARCHMENT ? 2 : 0;
		frame.glassTint = 1f - config.chromeTransparency() / 100f;
		frame.viewportX = client.getViewportXOffset();
		frame.viewportY = client.getViewportYOffset();
		frame.viewportWidth = client.getViewportWidth();
		frame.viewportHeight = client.getViewportHeight();
		fillUnderwater();
		fillRunoff();
		if (photo.takeQuad())
		{
			quadPhoto(width, height);
		}
		if (cinema.active() && !cinema.preview())
		{
			burst(config.cinemaBurst());
			cinema.frameRendered();
		}
		else if (cinema.active())
		{
			// A preview plays the path at the live frame rate with nothing saved.
			renderer.submit(frame, dynamic, dynamicTranslucent, dynamicWater, glSignalPending, true);
			cinema.framePreviewed();
		}
		else if (photo.takeBurst())
		{
			burst(config.photoBurst());
			if (config.linearExport())
			{
				float[] linear = renderer.readbackColor();
				int w = renderer.internalWidth();
				int h = renderer.internalHeight();
				float exposure = frame.exposure;
				drawManager.requestNextFrameListener(image -> photo.savePhotoAsync(image, linear, w, h, exposure));
			}
			else
			{
				drawManager.requestNextFrameListener(photo::savePhotoAsync);
			}
		}
		else
		{
			renderer.submit(frame, dynamic, dynamicTranslucent, dynamicWater, glSignalPending, true);
		}
		motion.endFrame();
		statSubmitNanos += System.nanoTime() - start;
		glSignalPending = false;
		sceneFramePending = true;

		++statFrames;
		++statInfoFrames;
		long end = System.nanoTime();
		statCpuNanos += end - frameCpuStart;
		if (end - statInfoReport > 30_000_000_000L)
		{
			// Frame timing at info level so the launcher's console log shows it without --debug.
			double seconds = (end - statInfoReport) / 1e9;
			long waited = renderer.waitNanos() - statWaitBase;
			statWaitBase = renderer.waitNanos();
			log.info("GPU {} ms per frame ({}); CPU {} ms per frame, {} of it waiting on the GPU; {} dynamic faces (actors {}, foliage {}, ground {}, water {}); {} local lights; {} fps over {} s",
				String.format("%.1f", renderer.lastGpuMillis()), renderer.passReport(),
				String.format("%.1f", statCpuNanos / 1e6 / Math.max(statInfoFrames, 1)), String.format("%.1f", waited / 1e6 / Math.max(statInfoFrames, 1)),
				dynamic.faces() + dynamicTranslucent.faces() + dynamicWater.faces(), actorFaces, foliageFaces, groundFaces, dynamicWater.faces(), frame.lightCount,
				String.format("%.1f", statInfoFrames / seconds), Math.round(seconds));
			statInfoReport = end;
			statInfoFrames = 0;
			statCpuNanos = 0;
		}
		if (start - statLastReport > 5_000_000_000L)
		{
			log.debug("frames={} dynamicCalls={} tempCalls={} droppedInactive={} droppedSubScene={} dynamicFaces={} submitAvgMs={} gpuMs={} offscreenActors={}",
				statFrames, statDynamicCalls, statTempCalls, statInactive, statSubScene, dynamic.faces() + dynamicTranslucent.faces(),
				String.format("%.2f", statSubmitNanos / 1_000_000.0 / Math.max(statFrames, 1)), String.format("%.2f", renderer.lastGpuMillis()),
				statOffscreen);
			environment.logSun();
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
			statOffscreen = 0;
			statSubScene = 0;
			statSubmitNanos = 0;
		}
	}

	// A photo at twice the width and height of the view, at showcase quality whatever is on: the
	// renderer's images are resized for the burst, the field of view held by doubling the zoom,
	// the result read straight back, and the view-sized images restored and handed back to OpenGL
	// for the frame that follows.
	private void quadPhoto(int width, int height)
	{
		Showcase.Held held = Showcase.maximise(frame, gameTexturesUploaded);
		float zoom = frame.zoom;
		float diffusionRadius = frame.diffusionRadius;
		frame.zoom = zoom * 2f;
		frame.diffusionRadius = diffusionRadius * 2f;
		renderer.ensureOutput(width * 2, height * 2, 1f, -1, false);
		burst(config.photoBurst(), false);
		glSignalPending = false;
		int[] argb = renderer.readbackOutput();
		float[] linear = config.linearExport() ? renderer.readbackColor() : null;
		float exposure = frame.exposure;
		frame.zoom = zoom;
		frame.diffusionRadius = diffusionRadius;
		renderer.ensureOutput(width, height, 1f, -1, false);
		compositor.importSceneImage(renderer.outputHandle(), renderer.outputAllocationSize(), width, height);
		held.restore(frame);
		photo.saveArgbAsync(argb, width * 2, height * 2, linear, exposure);
	}

	// A portrait of the character's outfit. On the plain stage their model stands alone on a grey
	// floor far outside the scene with the scene hidden; in a kept place it stands at that place's
	// spot among its surroundings; where they stand it is simply the character as they are, in
	// the world as it is. Lit by a key light from the camera's front left and the sky, framed from
	// the front at the height of the chest, accumulated like a photo, and saved. The live frame
	// lends its settings and gets them back afterwards.
	// The avatar goes through the non-freezing offscreen renderer when the GPU gave us a second
	// queue; otherwise it falls back to the synchronous portrait (which briefly freezes the frame).
	private boolean portraitOrAvatar(Player player, String name)
	{
		if (avatarRenderer != null && renderAvatarAsync(player, name))
		{
			return true;
		}
		return portrait(player, name);
	}

	private boolean portrait(Player player, String name)
	{
		Model model = player.getModel();
		if (renderer == null || !glReady || !gameTexturesUploaded || model == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		RltxConfig.PortraitScene choice = config.portraitScene();
		Stages.Place place = Stages.place(choice);
		Stages.Stage kept = null;
		if (place != null)
		{
			try
			{
				kept = stages.load(place);
			}
			catch (IOException e)
			{
				log.warn("Scene {} not loaded", place.label, e);
				say("RLTX: " + e.getMessage());
			}
			if (kept == null)
			{
				say("RLTX: the " + place.label + " scene is not kept yet; walk within a few tiles of its spot once. This portrait is posed on the stage.");
			}
		}
		boolean here = choice == RltxConfig.PortraitScene.HERE;
		// Where the figure stands, which way it faces, and from which side the camera looks.
		float originX, originY, originZ, cameraYaw;
		int orientation;
		if (here)
		{
			LocalPoint lp = player.getLocalLocation();
			if (lp == null)
			{
				return false;
			}
			originX = lp.getX();
			originZ = lp.getY();
			originY = Perspective.getTileHeight(client, lp, player.getWorldLocation().getPlane()) - player.getAnimationHeightOffset();
			orientation = player.getCurrentOrientation();
			cameraYaw = (float) (-orientation * Math.PI * 2.0 / 2048.0);
		}
		else
		{
			originX = -40000f;
			originY = 0f;
			originZ = -40000f;
			cameraYaw = kept != null ? place.cameraYaw : 0f;
			orientation = (int) Math.round(-cameraYaw / (2.0 * Math.PI) * 2048.0) & 2047;
		}
		float[] span = stage(model, originX, originY, originZ, orientation, kept, !here && kept == null);
		if (span == null)
		{
			return false;
		}
		float top = span[0], bottom = span[1];

		final int width = 1024, height = 1536;
		float modelHeight = Math.max(bottom - top, 60f);
		float distance = Math.max(3.2f * modelHeight, 600f);
		float centreY = top + 0.5f * modelHeight;
		float cameraY = top + 0.42f * modelHeight;
		float pitch = (float) Math.atan2(centreY - cameraY, distance);
		float sinYaw = (float) Math.sin(cameraYaw), cosYaw = (float) Math.cos(cameraYaw);

		// Everything of the live frame the portrait changes, put back afterwards.
		Showcase.Held held = Showcase.maximise(frame, true);
		float[] inverse = frame.inverseRotation.clone();
		float[] forward = frame.forwardRotation.clone();
		float cameraX = frame.cameraX, cameraYSaved = frame.cameraY, cameraZ = frame.cameraZ, zoom = frame.zoom;
		float sunX = frame.sunX, sunY = frame.sunY, sunZ = frame.sunZ, sunIntensity = frame.sunIntensity;
		float sunR = frame.sunR, sunG = frame.sunG, sunB = frame.sunB;
		float skyR = frame.skyR, skyG = frame.skyG, skyB = frame.skyB, ambient = frame.ambient, exposure = frame.exposure;
		float backgroundR = frame.backgroundR, backgroundG = frame.backgroundG, backgroundB = frame.backgroundB;
		boolean skybox = frame.skybox, proceduralSky = frame.proceduralSky, physicalSky = frame.physicalSky;
		boolean clouds = frame.clouds, cloudShadows = frame.cloudShadows, autoExposure = frame.autoExposure;
		float cloud = frame.cloud, fogAmount = frame.fogAmount, rain = frame.rain, snow = frame.snow, mist = frame.mist;
		float wetness = frame.wetness, snowCover = frame.snowCover, lightShafts = frame.lightShafts, unseenDarkness = frame.unseenDarkness;
		float aperture = frame.aperture, vignette = frame.vignette, filmGrain = frame.filmGrain, chromaticAberration = frame.chromaticAberration;
		float aerialPerspective = frame.aerialPerspective, distanceFade = frame.distanceFade, auroraWeight = frame.auroraWeight;
		int lightCount = frame.lightCount, printCount = frame.printCount, markerCount = frame.markerCount, plumeCount = frame.plumeCount, treeCount = frame.treeCount;
		boolean wildlife = frame.wildlife, fireflies = frame.fireflies, dustMotes = frame.dustMotes, mistEverywhere = frame.mistEverywhere;
		boolean rainbows = frame.rainbows, heatShimmer = frame.heatShimmer, puddles = frame.puddles, ripples = frame.ripples;
		float footprintStrength = frame.footprintStrength, starBrightness = frame.starBrightness;

		frame.cameraX = originX + sinYaw * distance;
		frame.cameraY = originY + cameraY;
		frame.cameraZ = originZ - cosYaw * distance;
		frame.zoom = height * distance / (1.3f * modelHeight);
		CameraMath.inverseRotation(pitch, cameraYaw, frame.inverseRotation);
		CameraMath.forwardRotation(pitch, cameraYaw, frame.forwardRotation);
		frame.aperture = 0f;
		frame.vignette = 0f;
		frame.filmGrain = 0f;
		frame.chromaticAberration = 0f;
		frame.unseenDarkness = 0f;
		if (!here)
		{
			// A key light from above and the camera's front left; up is -y.
			float keyLength = (float) Math.sqrt(0.45f * 0.45f + 0.75f * 0.75f + 0.55f * 0.55f);
			float keyX = -0.45f / keyLength, keyZ = -0.55f / keyLength;
			frame.sunX = keyX * cosYaw + keyZ * sinYaw;
			frame.sunY = -0.75f / keyLength;
			frame.sunZ = -keyX * sinYaw + keyZ * cosYaw;
			frame.sunIntensity = config.sunIntensity() / 100f;
			frame.sunR = 1f;
			frame.sunG = 0.97f;
			frame.sunB = 0.92f;
			boolean outdoors = kept != null;
			frame.skyR = outdoors ? 0.5f : 0.35f;
			frame.skyG = outdoors ? 0.6f : 0.4f;
			frame.skyB = outdoors ? 0.75f : 0.5f;
			frame.ambient = 0.25f;
			frame.exposure = config.exposure() / 100f;
			frame.backgroundR = outdoors ? 0.5f : 0.22f;
			frame.backgroundG = outdoors ? 0.65f : 0.22f;
			frame.backgroundB = outdoors ? 0.9f : 0.24f;
			frame.skybox = false;
			frame.proceduralSky = false;
			frame.physicalSky = false;
			frame.starBrightness = 0f;
			frame.clouds = false;
			frame.cloudShadows = false;
			frame.autoExposure = false;
			frame.cloud = 0f;
			frame.fogAmount = 0f;
			frame.rain = 0f;
			frame.snow = 0f;
			frame.mist = 0f;
			frame.wetness = 0f;
			frame.snowCover = 0f;
			frame.lightShafts = 0f;
			frame.aerialPerspective = 0f;
			frame.distanceFade = 0f;
			frame.auroraWeight = 0f;
			frame.lightCount = 0;
			frame.printCount = 0;
			frame.markerCount = 0;
			frame.plumeCount = 0;
			frame.treeCount = 0;
			frame.wildlife = false;
			frame.fireflies = false;
			frame.dustMotes = false;
			frame.mistEverywhere = false;
			frame.rainbows = false;
			frame.heatShimmer = false;
			frame.puddles = false;
			frame.footprintStrength = 0f;
			frame.ripples = false;
			// The scene itself is hidden: no level is visible and no roof is hidden.
			renderer.setStaticView(WorldView.TOPLEVEL, null, 99, 99, -1, java.util.Collections.emptySet());
		}

		int canvasWidth = client.getCanvasWidth();
		int canvasHeight = client.getCanvasHeight();
		renderer.ensureOutput(width, height, 1f, -1, false);
		burst(48, false, portraitOpaque, portraitTranslucent, portraitWater);
		glSignalPending = false;
		int[] argb = renderer.readbackOutput();

		// The idle cycle, pose by pose: the client is asked for the model at each frame of the
		// idle animation in turn, and each is rendered smaller and more briefly than the still.
		List<int[]> cycle = null;
		int[] durations = null;
		final int cycleWidth = 384, cycleHeight = 576;
		Animation idle = config.outfitAnimation() ? client.loadAnimation(player.getIdlePoseAnimation()) : null;
		if (idle != null && idle.getNumFrames() > 1)
		{
			int count = idle.getNumFrames();
			int[] lengths = idle.getFrameLengths();
			int step = (count + CYCLE_POSES - 1) / CYCLE_POSES;
			int savedPose = player.getPoseAnimationFrame();
			cycle = new ArrayList<>();
			List<Integer> millis = new ArrayList<>();
			frame.zoom = cycleHeight * distance / (1.3f * modelHeight);
			renderer.ensureOutput(cycleWidth, cycleHeight, 1f, -1, false);
			for (int f = 0; f < count; f += step)
			{
				player.setPoseAnimationFrame(f);
				Model posed = player.getModel();
				if (posed == null || stage(posed, originX, originY, originZ, orientation, kept, !here && kept == null) == null)
				{
					continue;
				}
				burst(CYCLE_SAMPLES, false, portraitOpaque, portraitTranslucent, portraitWater);
				cycle.add(renderer.readbackOutput());
				int duration = 0;
				for (int k = f; k < Math.min(count, f + step); ++k)
				{
					// Frame lengths are in client ticks of twenty milliseconds.
					duration += (lengths != null && k < lengths.length ? Math.max(lengths[k], 1) : 1) * 20;
				}
				millis.add(duration);
			}
			player.setPoseAnimationFrame(savedPose);
			durations = new int[millis.size()];
			for (int i = 0; i < durations.length; ++i)
			{
				durations[i] = millis.get(i);
			}
		}
		renderer.ensureOutput(canvasWidth, canvasHeight, 1f, -1, false);
		compositor.importSceneImage(renderer.outputHandle(), renderer.outputAllocationSize(), canvasWidth, canvasHeight);

		held.restore(frame);
		System.arraycopy(inverse, 0, frame.inverseRotation, 0, 9);
		System.arraycopy(forward, 0, frame.forwardRotation, 0, 9);
		frame.cameraX = cameraX;
		frame.cameraY = cameraYSaved;
		frame.cameraZ = cameraZ;
		frame.zoom = zoom;
		frame.sunX = sunX;
		frame.sunY = sunY;
		frame.sunZ = sunZ;
		frame.sunIntensity = sunIntensity;
		frame.sunR = sunR;
		frame.sunG = sunG;
		frame.sunB = sunB;
		frame.skyR = skyR;
		frame.skyG = skyG;
		frame.skyB = skyB;
		frame.ambient = ambient;
		frame.exposure = exposure;
		frame.backgroundR = backgroundR;
		frame.backgroundG = backgroundG;
		frame.backgroundB = backgroundB;
		frame.skybox = skybox;
		frame.proceduralSky = proceduralSky;
		frame.physicalSky = physicalSky;
		frame.clouds = clouds;
		frame.cloudShadows = cloudShadows;
		frame.autoExposure = autoExposure;
		frame.cloud = cloud;
		frame.fogAmount = fogAmount;
		frame.rain = rain;
		frame.snow = snow;
		frame.mist = mist;
		frame.wetness = wetness;
		frame.snowCover = snowCover;
		frame.lightShafts = lightShafts;
		frame.unseenDarkness = unseenDarkness;
		frame.aperture = aperture;
		frame.vignette = vignette;
		frame.filmGrain = filmGrain;
		frame.chromaticAberration = chromaticAberration;
		frame.aerialPerspective = aerialPerspective;
		frame.distanceFade = distanceFade;
		frame.auroraWeight = auroraWeight;
		frame.lightCount = lightCount;
		frame.printCount = printCount;
		frame.markerCount = markerCount;
		frame.plumeCount = plumeCount;
		frame.treeCount = treeCount;
		frame.wildlife = wildlife;
		frame.fireflies = fireflies;
		frame.dustMotes = dustMotes;
		frame.mistEverywhere = mistEverywhere;
		frame.rainbows = rainbows;
		frame.heatShimmer = heatShimmer;
		frame.puddles = puddles;
		frame.footprintStrength = footprintStrength;
		frame.starBrightness = starBrightness;
		frame.ripples = ripples;

		photo.saveOutfitAsync(argb, width, height, name);
		java.util.function.BiConsumer<byte[], String> upload = profilePictureSinks();
		if (cycle != null && cycle.size() > 1)
		{
			photo.saveOutfitAnimationAsync(cycle, durations, cycleWidth, cycleHeight, name, upload);
		}
		else if (upload != null)
		{
			try
			{
				upload.accept(Lyku.avatarPng(argb, width, height), "image/png");
			}
			catch (IOException e)
			{
				log.warn("Portrait not encoded for upload", e);
			}
		}
		return true;
	}

	// Where a new portrait goes as a profile picture: each linked account whose setting is on;
	// null when there is none. A setting on without its link says so.
	// burst(), but on the avatar renderer with the avatar frame; no GL wait/signal and no present,
	// since the avatar output is read back to CPU rather than composited to the canvas.
	private void avatarBurst(int frames, GeometryBuffer opaque, GeometryBuffer translucent, GeometryBuffer water)
	{
		avatarFrame.historyFrames = frames + 1;
		avatarFrame.dynamicHistoryFrames = frames + 1;
		avatarFrame.denoisePasses = 1;
		avatarFrame.shutter = 0f;
		avatarFrame.still = true;
		avatarFrame.thinLens = avatarFrame.aperture > 0f;
		int rippleSteps = avatarFrame.rippleSteps;
		avatarFrame.rippleSteps = 0;
		avatarRenderer.resetHistory();
		for (int i = 0; i <= frames; ++i)
		{
			avatarRenderer.submit(avatarFrame, opaque, translucent, water, false, false);
		}
		avatarFrame.rippleSteps = rippleSteps;
		avatarFrame.still = false;
		avatarFrame.thinLens = false;
		avatarRenderer.resetHistory();
	}

	/**
	 * Renders the animated avatar without freezing the game: the idle poses are captured on the
	 * client thread (model reads + cheap CPU geometry pushes), then a worker raytraces and reads
	 * back each pose on the second renderer and hands the frames to the existing WebP upload.
	 *
	 * First cut: the frame configuration below mirrors portrait()'s studio lighting; the per-scene
	 * lighting polish, resolution and sample count are worth tuning against a real render.
	 */
	private boolean renderAvatarAsync(Player player, String name)
	{
		if (avatarRenderer == null || !glReady || !gameTexturesUploaded || avatarBusy
			|| player.getModel() == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		RltxConfig.PortraitScene choice = config.portraitScene();
		Stages.Place place = Stages.place(choice);
		Stages.Stage kept = null;
		if (place != null)
		{
			try
			{
				kept = stages.load(place);
			}
			catch (IOException e)
			{
				log.warn("Scene {} not loaded", place.label, e);
			}
			if (kept == null)
			{
				say("RLTX: the " + place.label + " scene is not kept yet; walk within a few tiles of its spot once.");
			}
		}
		boolean here = choice == RltxConfig.PortraitScene.HERE;
		float originX, originY, originZ, cameraYaw;
		int orientation;
		if (here)
		{
			LocalPoint lp = player.getLocalLocation();
			if (lp == null)
			{
				return false;
			}
			originX = lp.getX();
			originZ = lp.getY();
			originY = Perspective.getTileHeight(client, lp, player.getWorldLocation().getPlane()) - player.getAnimationHeightOffset();
			orientation = player.getCurrentOrientation();
			cameraYaw = (float) (-orientation * Math.PI * 2.0 / 2048.0);
		}
		else
		{
			originX = -40000f;
			originY = 0f;
			originZ = -40000f;
			cameraYaw = kept != null ? place.cameraYaw : 0f;
			orientation = (int) Math.round(-cameraYaw / (2.0 * Math.PI) * 2048.0) & 2047;
		}
		final boolean bare = !here && kept == null;

		// Capture the idle poses on the client thread: one fresh buffer set per pose so the worker
		// can render them all without the client thread mutating geometry mid-render.
		Animation idle = config.outfitAnimation() ? client.loadAnimation(player.getIdlePoseAnimation()) : null;
		int count = idle != null && idle.getNumFrames() > 1 ? idle.getNumFrames() : 1;
		int[] lengths = idle != null ? idle.getFrameLengths() : null;
		int step = Math.max(1, (count + CYCLE_POSES - 1) / CYCLE_POSES);
		int savedPose = player.getPoseAnimationFrame();
		List<GeometryBuffer[]> poses = new ArrayList<>();
		List<Integer> millis = new ArrayList<>();
		float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
		for (int f = 0; f < count; f += step)
		{
			if (idle != null)
			{
				player.setPoseAnimationFrame(f);
			}
			Model posed = player.getModel();
			if (posed == null)
			{
				continue;
			}
			GeometryBuffer o = new GeometryBuffer(1 << 12);
			GeometryBuffer t = new GeometryBuffer(1 << 10);
			GeometryBuffer w = new GeometryBuffer(1 << 8);
			float[] span = stage(posed, originX, originY, originZ, orientation, kept, bare, o, t, w);
			if (span == null)
			{
				continue;
			}
			top = Math.min(top, span[0]);
			bottom = Math.max(bottom, span[1]);
			poses.add(new GeometryBuffer[]{o, t, w});
			int duration = 0;
			for (int k = f; k < Math.min(count, f + step); ++k)
			{
				duration += (lengths != null && k < lengths.length ? Math.max(lengths[k], 1) : 1) * 20;
			}
			millis.add(duration);
		}
		if (idle != null)
		{
			player.setPoseAnimationFrame(savedPose);
		}
		if (poses.isEmpty())
		{
			return false;
		}

		final int width = 512, height = 768;
		float modelHeight = Math.max(bottom - top, 60f);
		float distance = Math.max(3.2f * modelHeight, 600f);
		float centreY = top + 0.5f * modelHeight;
		float cameraY = top + 0.42f * modelHeight;
		float pitch = (float) Math.atan2(centreY - cameraY, distance);
		configureAvatarFrame(originX, originY, originZ, cameraY, distance, pitch, cameraYaw, modelHeight, height, kept != null);

		final java.util.function.BiConsumer<byte[], String> upload = profilePictureSinks();
		final int[] durations = new int[millis.size()];
		for (int i = 0; i < durations.length; ++i)
		{
			durations[i] = millis.get(i);
		}
		avatarBusy = true;
		Thread worker = new Thread(() ->
		{
			try
			{
				List<int[]> cycle = new ArrayList<>();
				avatarRenderer.ensureOutput(width, height, 1f, -1, false);
				for (GeometryBuffer[] pose : poses)
				{
					avatarBurst(CYCLE_SAMPLES, pose[0], pose[1], pose[2]);
					cycle.add(avatarRenderer.readbackOutput());
				}
				if (cycle.size() > 1)
				{
					photo.saveOutfitAnimationAsync(cycle, durations, width, height, name, upload);
				}
				else if (upload != null && !cycle.isEmpty())
				{
					upload.accept(Lyku.avatarPng(cycle.get(0), width, height), "image/png");
				}
			}
			catch (Exception e)
			{
				log.warn("Avatar render failed", e);
			}
			finally
			{
				avatarBusy = false;
			}
		}, "rltx-avatar");
		worker.setDaemon(true);
		worker.start();
		return true;
	}

	// Sets the avatar frame's camera (framed on the model) and a studio key light, and clears the
	// weather/skybox/effect state that would otherwise leak in from an unconfigured frame.
	private void configureAvatarFrame(float originX, float originY, float originZ, float cameraY, float distance,
		float pitch, float cameraYaw, float modelHeight, int height, boolean outdoors)
	{
		float sinYaw = (float) Math.sin(cameraYaw), cosYaw = (float) Math.cos(cameraYaw);
		avatarFrame.cameraX = originX + sinYaw * distance;
		avatarFrame.cameraY = originY + cameraY;
		avatarFrame.cameraZ = originZ - cosYaw * distance;
		avatarFrame.zoom = height * distance / (1.3f * modelHeight);
		CameraMath.inverseRotation(pitch, cameraYaw, avatarFrame.inverseRotation);
		CameraMath.forwardRotation(pitch, cameraYaw, avatarFrame.forwardRotation);
		avatarFrame.aperture = 0f;
		avatarFrame.vignette = 0f;
		avatarFrame.filmGrain = 0f;
		avatarFrame.chromaticAberration = 0f;
		avatarFrame.unseenDarkness = 0f;
		float keyLength = (float) Math.sqrt(0.45f * 0.45f + 0.75f * 0.75f + 0.55f * 0.55f);
		float keyX = -0.45f / keyLength, keyZ = -0.55f / keyLength;
		avatarFrame.sunX = keyX * cosYaw + keyZ * sinYaw;
		avatarFrame.sunY = -0.75f / keyLength;
		avatarFrame.sunZ = -keyX * sinYaw + keyZ * cosYaw;
		avatarFrame.sunIntensity = config.sunIntensity() / 100f;
		avatarFrame.sunR = 1f;
		avatarFrame.sunG = 0.97f;
		avatarFrame.sunB = 0.92f;
		avatarFrame.skyR = outdoors ? 0.5f : 0.35f;
		avatarFrame.skyG = outdoors ? 0.6f : 0.4f;
		avatarFrame.skyB = outdoors ? 0.75f : 0.5f;
		avatarFrame.ambient = 0.25f;
		avatarFrame.exposure = config.exposure() / 100f;
		avatarFrame.backgroundR = outdoors ? 0.5f : 0.22f;
		avatarFrame.backgroundG = outdoors ? 0.65f : 0.22f;
		avatarFrame.backgroundB = outdoors ? 0.9f : 0.24f;
		avatarFrame.skybox = false;
		avatarFrame.proceduralSky = false;
		avatarFrame.physicalSky = false;
		avatarFrame.starBrightness = 0f;
		avatarFrame.clouds = false;
		avatarFrame.cloudShadows = false;
		avatarFrame.autoExposure = false;
		avatarFrame.cloud = 0f;
		avatarFrame.fogAmount = 0f;
		avatarFrame.rain = 0f;
		avatarFrame.snow = 0f;
		avatarFrame.mist = 0f;
		avatarFrame.wetness = 0f;
		avatarFrame.snowCover = 0f;
		avatarFrame.lightShafts = 0f;
		avatarFrame.aerialPerspective = 0f;
		avatarFrame.distanceFade = 0f;
		avatarFrame.auroraWeight = 0f;
		avatarFrame.lightCount = 0;
		avatarFrame.printCount = 0;
		avatarFrame.markerCount = 0;
		avatarFrame.plumeCount = 0;
		avatarFrame.treeCount = 0;
		avatarFrame.wildlife = false;
		avatarFrame.fireflies = false;
		avatarFrame.dustMotes = false;
		avatarFrame.mistEverywhere = false;
		avatarFrame.rainbows = false;
		avatarFrame.heatShimmer = false;
		avatarFrame.puddles = false;
		avatarFrame.footprintStrength = 0f;
		avatarFrame.ripples = false;
		avatarFrame.textureSize = frame.textureSize;
		avatarFrame.still = true;
	}

	private java.util.function.BiConsumer<byte[], String> profilePictureSinks()
	{
		boolean org = config.lykuOrgAvatar();
		boolean co = config.lykuAvatar();
		if (org && !lykuOrg.connected())
		{
			say("Lyku.org: not connected, so the profile picture stays; tick Connect to Lyku.org in the settings");
			org = false;
		}
		if (co && !lyku.connected())
		{
			say("Lyku.co: not connected, so the profile picture stays; tick Connect to a Lyku.co workspace in the settings");
			co = false;
		}
		if (!org && !co)
		{
			return null;
		}
		final boolean toOrg = org, toCo = co;
		return (bytes, mime) ->
		{
			if (toOrg)
			{
				lykuOrg.setProfilePictureAsync(bytes, mime);
			}
			if (toCo)
			{
				lyku.setAvatarBytesAsync(bytes, mime);
			}
		};
	}

	/** How many poses of the idle cycle are rendered at most, and how many samples each gets. */
	private static final int CYCLE_POSES = 20;
	private static final int CYCLE_SAMPLES = 12;

	// Fills the portrait buffers with a model standing at the origin facing the given way, with
	// either a kept place's surroundings around it or, when asked, a grey floor under it; returns
	// the model's top and bottom relative to the origin, or null when it has no faces.
	private float[] stage(Model model, float originX, float originY, float originZ, int orientation, Stages.Stage kept, boolean floor)
	{
		return stage(model, originX, originY, originZ, orientation, kept, floor, portraitOpaque, portraitTranslucent, portraitWater);
	}

	// Pushes the player model (plus a kept backdrop or a grey floor) into the given buffers and
	// returns its vertical span, so both the live portrait and the offscreen avatar can stage.
	private float[] stage(Model model, float originX, float originY, float originZ, int orientation, Stages.Stage kept, boolean floor,
		GeometryBuffer opaque, GeometryBuffer translucent, GeometryBuffer water)
	{
		opaque.clear();
		translucent.clear();
		water.clear();
		framePusher.actor = true;
		framePusher.push(model, orientation, (int) originX, (int) originY, (int) originZ, null, palette(), opaque, translucent);
		framePusher.actor = false;
		int faces = opaque.faces();
		if (faces == 0)
		{
			return null;
		}
		float[] pos = opaque.positions();
		float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
		for (int i = 0; i < faces * GeometryBuffer.FLOATS_PER_FACE; i += 3)
		{
			top = Math.min(top, pos[i + 1] - originY);
			bottom = Math.max(bottom, pos[i + 1] - originY);
		}
		if (kept != null)
		{
			opaque.append(kept.opaque, originX, originY, originZ);
			translucent.append(kept.translucent, originX, originY, originZ);
			water.append(kept.water, originX, originY, originZ);
		}
		else if (floor)
		{
			final float half = 1500f;
			int grey = 0xff6a6a6a;
			opaque.face(originX - half, originY, originZ - half, originX + half, originY, originZ + half, originX + half, originY, originZ - half, grey);
			opaque.face(originX - half, originY, originZ - half, originX - half, originY, originZ + half, originX + half, originY, originZ + half, grey);
		}
		return new float[]{top, bottom};
	}

	// The portrait of the outfit worn now, as already on disk, put up as the profile picture of
	// every linked account whose setting is on.
	private void pushOutfitToLyku()
	{
		String name = outfits.currentName();
		java.util.function.BiConsumer<byte[], String> upload = name == null ? null : profilePictureSinks();
		if (upload == null)
		{
			return;
		}
		File webp = new File(Outfits.FOLDER, name + ".webp");
		File png = new File(Outfits.FOLDER, name + ".png");
		File file = webp.exists() ? webp : png.exists() ? png : null;
		if (file == null)
		{
			return;
		}
		try
		{
			byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
			if (webp.exists())
			{
				upload.accept(bytes, "image/webp");
			}
			else
			{
				// The still on disk is the full portrait; the picture is its head and shoulders.
				BufferedImage image = ImageIO.read(file);
				int[] argb = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
				upload.accept(Lyku.avatarPng(argb, image.getWidth(), image.getHeight()), "image/png");
			}
		}
		catch (IOException e)
		{
			log.warn("Outfit {} not read for upload", file, e);
			say("RLTX: could not read " + file.getName());
		}
	}

	// Holds this frame's scene still and accumulates many more samples of it before it is shown,
	// so the photo taken of it has neither noise nor denoiser blur. The client waits meanwhile.
	private void burst(int frames)
	{
		burst(frames, true);
	}

	// A burst that is not presented leaves OpenGL's semaphore alone, since nothing will wait on it.
	private void burst(int frames, boolean present)
	{
		burst(frames, present, dynamic, dynamicTranslucent, dynamicWater);
	}

	private void burst(int frames, boolean present, GeometryBuffer opaque, GeometryBuffer translucent, GeometryBuffer water)
	{
		frame.historyFrames = frames + 1;
		frame.dynamicHistoryFrames = frames + 1;
		frame.denoisePasses = 1;
		frame.shutter = 0f;
		// The lens becomes real for the burst, its samples averaging into true bokeh. Held frames
		// accumulate in different units from live ones, so the history is dropped on either side.
		frame.still = true;
		frame.thinLens = frame.aperture > 0f;
		// The burst is one instant: the wave clock already stands still across it, and the
		// ripple field is held too, or its rings would run on through every frame and average away.
		int rippleSteps = frame.rippleSteps;
		frame.rippleSteps = 0;
		renderer.resetHistory();
		for (int i = 0; i <= frames; ++i)
		{
			renderer.submit(frame, opaque, translucent, water, i == 0 && glSignalPending, present && i == frames);
		}
		frame.rippleSteps = rippleSteps;
		frame.still = false;
		frame.thinLens = false;
		renderer.resetHistory();
	}

	// The client decodes its textures lazily; once every one is available they are gathered on
	// the client thread, enlarged and given mip levels on a worker, then uploaded as one array.
	// Brightness is forced to 1 so the gamma is applied only by us.
	private boolean texturesBusy;

	private void ensureGameTextures()
	{
		if (gameTexturesUploaded || texturesBusy || !(config.textures() || config.terrainTextures()))
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
		if (textures.length > GroundTextures.BASE)
		{
			throw new IllegalStateException("The client has " + textures.length + " textures; ground detail layers start at " + GroundTextures.BASE);
		}

		final int size = 128;
		int[][] pixels = new int[textures.length][];
		float[] scroll = new float[textures.length * 2];
		double brightness = provider.getBrightness();
		provider.setBrightness(1.0);
		for (int id = 0; id < textures.length; ++id)
		{
			Texture texture = textures[id];
			if (texture == null)
			{
				continue;
			}
			int[] loaded = provider.load(id);
			if (loaded == null || loaded.length != size * size)
			{
				log.warn("Texture {} has {} pixels; expected {}x{}", id, loaded == null ? 0 : loaded.length, size, size);
				continue;
			}
			pixels[id] = loaded.clone();
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
		provider.setBrightness(brightness);

		int target = config.textureUpscale() == RltxConfig.TextureUpscale.OFF ? size : size * 4;
		File folder = new File(RuneLite.RUNELITE_DIR, "rltx/textures");
		texturesBusy = true;
		Thread worker = new Thread(() ->
		{
			int layers = GroundTextures.BASE + GroundTextures.layerCount();
			int levels = TextureUpscaler.levels(target);
			long total = 0;
			for (int level = 0; level < levels; ++level)
			{
				total += (long) layers * (target >> level) * (target >> level) * 4;
			}
			ByteBuffer packed = MemoryUtil.memCalloc((int) total);
			ByteBuffer relief = MemoryUtil.memCalloc((int) (total / 4));
			java.util.stream.IntStream.range(0, pixels.length).parallel().forEach(id ->
			{
				int[] source = pixels[id];
				if (source == null)
				{
					return;
				}
				// The client marks a transparent texel with a zero pixel; here it becomes zero alpha.
				int[] argb = new int[source.length];
				for (int i = 0; i < source.length; ++i)
				{
					argb[i] = source[i] == 0 ? 0 : 0xff000000 | source[i];
				}
				TextureUpscaler.export(new File(folder, "original/" + id + ".png"), argb, size);
				int[] big = TextureUpscaler.upscaled(argb, size, target, new File(folder, "upscaled/" + id + ".png"));
				writeLayer(packed, 0, layers, target, id, big);
			});
			GroundTextures.pack(packed, target);
			java.util.stream.IntStream.range(0, layers).parallel().forEach(layer ->
			{
				int[] argb = readLayer(packed, layers, target, layer);
				byte[] heights = TextureUpscaler.relief(argb, target);
				writeGray(relief, 0, layers, target, layer, heights);
				int extent = target;
				long offset = 0;
				for (int level = 1; level < levels; ++level)
				{
					offset += (long) layers * extent * extent * 4;
					argb = TextureUpscaler.halved(argb, extent);
					heights = TextureUpscaler.halvedGray(heights, extent);
					extent /= 2;
					writeLayer(packed, offset, layers, extent, layer, argb);
					writeGray(relief, offset / 4, layers, extent, layer, heights);
				}
			});
			java.util.BitSet cutouts = TextureUpscaler.cutouts(pixels);
			clientThread.invoke(() ->
			{
				try
				{
					if (renderer != null)
					{
						renderer.setTextureAnimation(scroll);
						renderer.setTextureArray(layers, target, levels, packed);
						renderer.setReliefArray(layers, target, levels, relief);
						if (avatarRenderer != null)
						{
							avatarRenderer.setTextureAnimation(scroll);
							avatarRenderer.setTextureArray(layers, target, levels, packed);
							avatarRenderer.setReliefArray(layers, target, levels, relief);
						}
						frame.textureSize = target;
						gameTexturesUploaded = true;
						// Faces with cutout textures need the non-opaque path; reclassify the static scene.
						TextureCutouts.set(cutouts);
						staticDirty = true;
						log.info("Uploaded {} game textures at {}x{}, {} with cutouts", pixels.length, target, target, cutouts.cardinality());
					}
				}
				finally
				{
					MemoryUtil.memFree(packed);
					MemoryUtil.memFree(relief);
					texturesBusy = false;
				}
			});
		}, "rltx-textures");
		worker.setDaemon(true);
		worker.start();
	}

	// One layer of one mip level of the packed array, as RGBA bytes; a zero alpha texel stays zero.
	private static void writeLayer(ByteBuffer packed, long levelOffset, int layers, int extent, int layer, int[] argb)
	{
		int base = (int) (levelOffset + (long) layer * extent * extent * 4);
		for (int i = 0; i < argb.length; ++i)
		{
			int p = argb[i];
			int o = base + i * 4;
			packed.put(o, (byte) (p >> 16)).put(o + 1, (byte) (p >> 8)).put(o + 2, (byte) p).put(o + 3, (byte) (p >>> 24));
		}
	}

	private static void writeGray(ByteBuffer packed, long levelOffset, int layers, int extent, int layer, byte[] gray)
	{
		int base = (int) (levelOffset + (long) layer * extent * extent);
		for (int i = 0; i < gray.length; ++i)
		{
			packed.put(base + i, gray[i]);
		}
	}

	private static int[] readLayer(ByteBuffer packed, int layers, int extent, int layer)
	{
		int base = layer * extent * extent * 4;
		int[] argb = new int[extent * extent];
		for (int i = 0; i < argb.length; ++i)
		{
			int o = base + i * 4;
			argb[i] = (packed.get(o + 3) & 0xff) << 24 | (packed.get(o) & 0xff) << 16 | (packed.get(o + 1) & 0xff) << 8 | (packed.get(o + 2) & 0xff);
		}
		return argb;
	}

	// The client's own renderer draws the next frame as a baseline beside a photo: the GPU flags
	// and the callbacks come off for that one frame and go back as soon as it has been drawn, so
	// the client rasterises the scene itself, interface and all, and hands the image over.
	private void captureStock()
	{
		String stem = stockStem;
		stockStem = null;
		client.setDrawCallbacks(null);
		client.setGpuFlags(0);
		drawManager.requestNextFrameListener(image ->
		{
			client.setGpuFlags(GPU_FLAGS);
			client.setDrawCallbacks(this);
			photo.saveStockAsync(image, stem);
		});
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
		if (config.chrome().sheet() && gameState == GameState.LOGGED_IN && renderer != null)
		{
			// The panes of this frame's interface become the sheets the next frame traces.
			renderer.setUiMask(bufferProvider.getPixels(), bufferProvider.getWidth(), bufferProvider.getHeight(), Chrome.GLASS_KEY);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glClearColor(0, 0, 0, 1);
		glClear(GL_COLOR_BUFFER_BIT);

		AffineTransform dpi = clientUI.getGraphicsConfiguration().getDefaultTransform();
		boolean stretched = client.isStretchedEnabled();
		Dimension stretchedDim = stretched ? client.getStretchedDimensions() : null;
		int targetWidth = stretched ? stretchedDim.width : canvasWidth;
		int targetHeight = stretched ? stretchedDim.height : canvasHeight;

		boolean sceneDrawn = false;
		if (sceneFramePending)
		{
			drawSceneQuad(dpi, canvasWidth, canvasHeight, stretched, stretchedDim);
			sceneFramePending = false;
			glSignalPending = true;
			sceneDrawn = true;
		}
		else if (config.loginPattern() && gameState != GameState.LOGGED_IN && gameState != GameState.LOADING)
		{
			if (renderer.ensureOutput(canvasWidth, canvasHeight, 1f, -1, false))
			{
				compositor.importSceneImage(renderer.outputHandle(), renderer.outputAllocationSize(), canvasWidth, canvasHeight);
			}
			frame.pattern = true;
			renderer.submit(frame, empty, empty, empty, glSignalPending, true);
			glSignalPending = false;
			compositor.drawScene(0, 0, scaled(dpi.getScaleX(), targetWidth), scaled(dpi.getScaleY(), targetHeight));
			glSignalPending = true;
			sceneDrawn = true;
			if (!patternSampled)
			{
				patternSampled = true;
				int texel = compositor.readSceneTexel(canvasWidth / 2, canvasHeight / 2, canvasWidth, canvasHeight);
				log.info("Login pattern centre texel (expect roughly r=g=0x7f or 0x4c): 0x{}", Integer.toHexString(texel));
			}
		}

		if (!photo.chromeHidden)
		{
			// Liquid glass reads the scene behind the chrome, so only a frame that drew one this
			// pass, and so holds the image, may have it; the chrome's panes stay solid otherwise.
			boolean glass = sceneDrawn && gameState == GameState.LOGGED_IN && config.chrome().sheet();
			compositor.setGlass(glass, Chrome.GLASS_KEY, 1f - config.chromeTransparency() / 100f, 4f, 10f, 1f,
				client.getViewportXOffset(), client.getViewportYOffset(), client.getViewportWidth(), client.getViewportHeight());
			compositor.drawUi(overlayColor, 0, 0, scaled(dpi.getScaleX(), targetWidth), scaled(dpi.getScaleY(), targetHeight));
		}
		if (sceneDrawn)
		{
			compositor.releaseScene();
		}
		drawManager.processDrawComplete(PhotoMode::screenshot);
		if (stockStem != null && gameState == GameState.LOGGED_IN)
		{
			captureStock();
		}

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
