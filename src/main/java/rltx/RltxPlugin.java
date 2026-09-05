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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import net.runelite.api.PlayerComposition;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.kit.KitType;
import net.runelite.api.Projectile;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Texture;
import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.runelite.api.ChatMessageType;
import net.runelite.client.RuneLite;
import java.awt.event.KeyEvent;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.util.HotkeyListener;
import net.runelite.api.Actor;
import net.runelite.api.Constants;
import net.runelite.api.FloatProjection;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.TextureProvider;
import net.runelite.api.coords.LocalPoint;
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
import rltx.scene.WaterSim;
import rltx.scene.lights.LightDefinition;
import rltx.scene.lights.LightLibrary;
import rltx.scene.lights.SceneLights;
import rltx.sky.Skybox;
import rltx.sky.SkyboxLoader;
import rltx.sky.SolarPosition;
import rltx.sky.GeoLocation;
import rltx.sky.WeatherService;
import rltx.sky.WeatherState;
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
	private NpcOverlayService npcOverlayService;

	// Photo mode: the interface layer is left out of the composite, and two corners of the view
	// act as invisible buttons, top-left to restore it and bottom-right to save a photo.
	private static final int PHOTO_BUTTON = 96;
	private volatile boolean chromeHidden;
	private final HotkeyListener photoModeKey = new HotkeyListener(() -> config.photoModeKey())
	{
		@Override
		public void hotkeyPressed()
		{
			chromeHidden = !chromeHidden;
		}
	};
	private final MouseAdapter photoButtons = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (!chromeHidden)
			{
				return e;
			}
			int w = client.getCanvasWidth();
			int h = client.getCanvasHeight();
			if (e.getX() < PHOTO_BUTTON && e.getY() < PHOTO_BUTTON)
			{
				chromeHidden = false;
				e.consume();
			}
			else if (e.getX() > w - PHOTO_BUTTON && e.getY() > h - PHOTO_BUTTON)
			{
				takePhoto();
				e.consume();
			}
			return e;
		}
	};

	// Free camera: detached from the client's, flown with the keyboard and turned by middle-drag.
	private volatile boolean freeCamera;
	private float freeX, freeY, freeZ, freePitch, freeYaw;
	private float clientCamX, clientCamY, clientCamZ, clientPitch, clientYaw;
	private long freeCameraNanos;
	private final Set<Integer> heldKeys = ConcurrentHashMap.newKeySet();
	private int lookX, lookY;
	private boolean looking;
	private static final Set<Integer> FLIGHT_KEYS = Set.of(KeyEvent.VK_W, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_Q, KeyEvent.VK_E, KeyEvent.VK_SHIFT);
	private final HotkeyListener freeCameraKey = new HotkeyListener(() -> config.freeCameraKey())
	{
		@Override
		public void hotkeyPressed()
		{
			freeCamera = !freeCamera;
			if (freeCamera)
			{
				freeX = clientCamX;
				freeY = clientCamY;
				freeZ = clientCamZ;
				freePitch = clientPitch;
				freeYaw = clientYaw;
				freeCameraNanos = 0;
			}
			heldKeys.clear();
		}
	};
	private final KeyListener flightKeys = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
			if (freeCamera && "wasdqeWASDQE".indexOf(e.getKeyChar()) >= 0)
			{
				e.consume();
			}
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (freeCamera && FLIGHT_KEYS.contains(e.getKeyCode()))
			{
				heldKeys.add(e.getKeyCode());
				e.consume();
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
			if (heldKeys.remove(e.getKeyCode()) && freeCamera)
			{
				e.consume();
			}
		}
	};
	private final MouseAdapter freeLook = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (freeCamera && e.getButton() == MouseEvent.BUTTON2)
			{
				looking = true;
				lookX = e.getX();
				lookY = e.getY();
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent e)
		{
			if (looking)
			{
				// Dragging pulls the world with the mouse, as the client's own camera drag does.
				freeYaw -= (e.getX() - lookX) * 0.004f;
				freePitch = Math.max(-1.45f, Math.min(1.45f, freePitch - (e.getY() - lookY) * 0.004f));
				lookX = e.getX();
				lookY = e.getY();
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			if (looking && e.getButton() == MouseEvent.BUTTON2)
			{
				looking = false;
				e.consume();
			}
			return e;
		}
	};

	// Advances the detached camera by the held keys since the last frame.
	private void flyFreeCamera()
	{
		long now = System.nanoTime();
		float dt = freeCameraNanos == 0 ? 0f : Math.min((now - freeCameraNanos) / 1e9f, 0.1f);
		freeCameraNanos = now;
		float speed = 6f * Perspective.LOCAL_TILE_SIZE * config.freeCameraSpeed() / 100f * (heldKeys.contains(KeyEvent.VK_SHIFT) ? 3f : 1f) * dt;
		float[] inv = new float[9];
		CameraMath.inverseRotation(freePitch, freeYaw, inv);
		float forward = (heldKeys.contains(KeyEvent.VK_W) ? 1f : 0f) - (heldKeys.contains(KeyEvent.VK_S) ? 1f : 0f);
		float right = (heldKeys.contains(KeyEvent.VK_D) ? 1f : 0f) - (heldKeys.contains(KeyEvent.VK_A) ? 1f : 0f);
		float up = (heldKeys.contains(KeyEvent.VK_E) ? 1f : 0f) - (heldKeys.contains(KeyEvent.VK_Q) ? 1f : 0f);
		freeX += (inv[2] * forward + inv[0] * right) * speed;
		freeY += (inv[5] * forward + inv[3] * right) * speed - up * speed;
		freeZ += (inv[8] * forward + inv[6] * right) * speed;
	}

	private volatile boolean burstPending;

	private void takePhoto()
	{
		if (config.photoBurst() > 0)
		{
			burstPending = true;
		}
		else
		{
			drawManager.requestNextFrameListener(this::savePhotoAsync);
		}
	}

	private void savePhotoAsync(Image image)
	{
		Thread saver = new Thread(() -> savePhoto(image), "rltx-photo");
		saver.setDaemon(true);
		saver.start();
	}

	// Holds this frame's scene still and accumulates many more samples of it before it is shown,
	// so the photo taken of it has neither noise nor denoiser blur. The client waits meanwhile.
	private void burst()
	{
		int frames = config.photoBurst();
		frame.historyFrames = frames + 1;
		frame.dynamicHistoryFrames = frames + 1;
		frame.denoisePasses = 1;
		frame.shutter = 0f;
		for (int i = 0; i <= frames; ++i)
		{
			if (i > 0)
			{
				renderer.beginFrame();
			}
			renderer.submit(frame, dynamic, dynamicTranslucent, i == 0 && glSignalPending, i == frames);
		}
		drawManager.requestNextFrameListener(this::savePhotoAsync);
	}

	private void savePhoto(Image image)
	{
		File dir = new File(RuneLite.SCREENSHOT_DIR, "RLTX");
		if (!dir.exists() && !dir.mkdirs())
		{
			log.warn("Could not create {}", dir);
			return;
		}
		File file = new File(dir, "photo-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".png");
		BufferedImage buffered = image instanceof BufferedImage ? (BufferedImage) image : null;
		if (buffered == null)
		{
			buffered = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = buffered.createGraphics();
			g.drawImage(image, 0, 0, null);
			g.dispose();
		}
		try
		{
			ImageIO.write(buffered, "png", file);
		}
		catch (IOException e)
		{
			log.warn("Photo not saved to {}", file, e);
			return;
		}
		log.info("Photo saved to {}", file);
		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Photo saved to " + file.getName(), null));
	}

	// The finished frame as the interface sees it, read back from the framebuffer the client is
	// about to present; the viewport holds its size in device pixels.
	private Image screenshot()
	{
		int[] viewport = new int[4];
		org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, viewport);
		int width = viewport[2];
		int height = viewport[3];
		ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
		try
		{
			org.lwjgl.opengl.GL11.glReadPixels(0, 0, width, height, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			int[] row = new int[width];
			for (int y = 0; y < height; ++y)
			{
				int base = (height - 1 - y) * width * 4;
				for (int x = 0; x < width; ++x)
				{
					int o = base + x * 4;
					row[x] = 0xff000000 | (pixels.get(o) & 0xff) << 16 | (pixels.get(o + 1) & 0xff) << 8 | (pixels.get(o + 2) & 0xff);
				}
				image.setRGB(0, y, width, 1, row, 0, width);
			}
			return image;
		}
		finally
		{
			MemoryUtil.memFree(pixels);
		}
	}

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	private WeatherService weatherService;
	private GeoLocation geoLocation;

	// Real time and place takes the machine's own location; the other modes take the settings.
	private double latitude()
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

	private double longitude()
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
		boolean wet = top.water.step(weatherDt, weatherNow.rain);
		if (wet || !runoffUploaded)
		{
			renderer.setRunoff(top.water.packed());
			runoffUploaded = !wet;
		}
	}
	private volatile LightLibrary lightLibrary;

	private synchronized LightLibrary lightLibrary()
	{
		if (lightLibrary == null)
		{
			lightLibrary = LightLibrary.load(gson);
		}
		return lightLibrary;
	}

	// Objects that carry a light in 117 HD's data: their hot-coloured faces are flames.
	private boolean hasLight(int objectId)
	{
		return lightLibrary().byObject.containsKey(objectId);
	}

	private static final String[] TREE_WORDS = {"tree", "oak", "willow", "yew", "maple", "palm", "mahogany", "teak", "redwood"};
	private static final String[] FOLIAGE_WORDS = {"bush", "shrub", "fern", "leaves", "plant", "flower", "grass", "reed", "vine", "hedge"};
	private static final String[] GRAVE_WORDS = {"grave", "tomb", "coffin", "headstone", "crypt", "sarcophag", "mausoleum"};
	private static final Pattern FIRE_WORDS = Pattern.compile("\\b(fire|campfire|bonfire|brazier|forge|furnace|range|pyre|hearth|fireplace|stove|oven)\\b", Pattern.CASE_INSENSITIVE);
	/** 0 rigid, 1 foliage that sways, 2 a tree that sways and scales, 3 a grave that gathers mist, 4 a chimney or fire that smokes. */
	private final Map<Integer, Integer> foliageIds = new ConcurrentHashMap<>();
	private static final float SWAY_RANGE = 24 * Perspective.LOCAL_TILE_SIZE;
	private static final int SWAY_FACE_BUDGET = 150_000;
	private float[] swayScratch = new float[0];

	private int foliageKind(int objectId)
	{
		return foliageIds.getOrDefault(objectId, 0);
	}

	private boolean isMisty(int objectId)
	{
		return foliageKind(objectId) == 3;
	}

	// Whether 117 HD describes the object's light as a fire of some kind, so smoke rises from it.
	private boolean smokes(int objectId)
	{
		List<LightDefinition> defs = lightLibrary().byObject.get(objectId);
		if (defs == null)
		{
			return false;
		}
		for (LightDefinition def : defs)
		{
			if (def.description != null && FIRE_WORDS.matcher(def.description).find())
			{
				return true;
			}
		}
		return false;
	}

	// Object names live in the client's cache, which the scene loader thread must not touch, so
	// unknown ids are resolved on the client thread first, as the GPU plugin does for its uploads.
	private void classifyFoliage(Set<Integer> ids)
	{
		List<Integer> unknown = new ArrayList<>();
		for (Integer id : ids)
		{
			if (!foliageIds.containsKey(id))
			{
				unknown.add(id);
			}
		}
		if (unknown.isEmpty())
		{
			return;
		}
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (Integer id : unknown)
			{
				ObjectComposition def = client.getObjectDefinition(id);
				String name = def == null || def.getName() == null ? "" : def.getName().toLowerCase(Locale.ROOT);
				int kind = 0;
				if (!name.contains("stump"))
				{
					for (String word : TREE_WORDS)
					{
						if (name.contains(word))
						{
							kind = 2;
							break;
						}
					}
					for (int i = 0; kind == 0 && i < FOLIAGE_WORDS.length; ++i)
					{
						if (name.contains(FOLIAGE_WORDS[i]))
						{
							kind = 1;
						}
					}
					for (int i = 0; kind == 0 && i < GRAVE_WORDS.length; ++i)
					{
						if (name.contains(GRAVE_WORDS[i]))
						{
							kind = 3;
						}
					}
					if (kind == 0 && (name.contains("chimney") || smokes(id)))
					{
						kind = 4;
					}
				}
				foliageIds.put(id, kind);
			}
			latch.countDown();
		});
		try
		{
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	// Foliage near the camera is drawn as swayed copies through the dynamic path each frame,
	// its static group skipped; the wind is a slow gust field with the weather's wind on top.
	private void pushFoliage()
	{
		LoadedScene top = scenes.get(WorldView.TOPLEVEL);
		if (!config.foliageWind() || top == null)
		{
			renderer.setSwayedZones(WorldView.TOPLEVEL, null);
			return;
		}
		StaticScene built = top.built;
		if (top.swayed == null || top.swayed.length != built.zones.length)
		{
			top.swayed = new boolean[built.zones.length];
		}
		float t = frame.timeSeconds;
		float amplitude = (4f + 10f * weatherNow.wind) * config.foliageWindStrength() / 100f;
		double to = Math.toRadians(weatherNow.windFromDegrees + 180.0);
		float dirX = (float) Math.sin(to);
		float dirZ = (float) Math.cos(to);
		int offsetTiles = (built.zonesX * 8 - Constants.SCENE_SIZE) / 2;
		int budget = SWAY_FACE_BUDGET;
		for (int i = 0; i < built.zones.length; ++i)
		{
			StaticScene.Zone zone = built.zones[i];
			top.swayed[i] = false;
			if (zone == null || zone.sway.faces() == 0 || budget < zone.sway.faces())
			{
				continue;
			}
			float centreX = ((i / built.zonesZ) * 8 - offsetTiles + 4) * Perspective.LOCAL_TILE_SIZE;
			float centreZ = ((i % built.zonesZ) * 8 - offsetTiles + 4) * Perspective.LOCAL_TILE_SIZE;
			float dx = centreX - frame.cameraX;
			float dz = centreZ - frame.cameraZ;
			if (dx * dx + dz * dz > SWAY_RANGE * SWAY_RANGE)
			{
				continue;
			}
			top.swayed[i] = true;
			budget -= zone.sway.faces();
			int faces = zone.sway.faces();
			float[] pos = zone.sway.positions();
			float[] weights = zone.swayWeights;
			int[] colors = zone.sway.colors();
			int[] textures = zone.sway.textures();
			float[] uvs = zone.sway.uvs();
			if (swayScratch.length < faces * 9)
			{
				swayScratch = new float[faces * 9];
			}
			int start = dynamic.faces();
			for (int f = 0; f < faces; ++f)
			{
				int o = f * 9;
				float px = pos[o];
				float pz = pos[o + 2];
				float gust = amplitude * ((float) Math.sin(t * 1.1 + px * 0.006 + pz * 0.004) + 0.5f * (float) Math.sin(t * 2.3 + pz * 0.011));
				float ox = gust * (0.6f * dirX + 0.4f * (float) Math.sin(t * 0.7 + px * 0.01));
				float oz = gust * (0.6f * dirZ + 0.4f * (float) Math.cos(t * 0.9 + pz * 0.008));
				for (int v = 0; v < 3; ++v)
				{
					float w = weights[f * 3 + v];
					swayScratch[o + v * 3] = pos[o + v * 3] + ox * w;
					swayScratch[o + v * 3 + 1] = pos[o + v * 3 + 1];
					swayScratch[o + v * 3 + 2] = pos[o + v * 3 + 2] + oz * w;
				}
				int uo = f * 6;
				dynamic.face(swayScratch[o], swayScratch[o + 1], swayScratch[o + 2], swayScratch[o + 3], swayScratch[o + 4], swayScratch[o + 5],
					swayScratch[o + 6], swayScratch[o + 7], swayScratch[o + 8], colors[f], textures[f],
					uvs[uo], uvs[uo + 1], uvs[uo + 2], uvs[uo + 3], uvs[uo + 4], uvs[uo + 5]);
			}
			dynamic.setPreviousPositions(start, swayScratch, faces);
		}
		renderer.setSwayedZones(WorldView.TOPLEVEL, top.swayed);
	}

	// The Shortest Path plugin's route, refreshed each game tick and drawn as a ribbon of light in
	// the composite pass instead of the plugin's own tile outlines.
	private ShortestPath shortestPath;
	private WorldPoint[] route;
	private final float[] guidePacked = new float[(RtRenderer.MAX_GUIDE_POINTS + 1) * 4];

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (config.pathGlow() && shortestPath.bind())
		{
			shortestPath.hideTileOverlay();
			route = shortestPath.route();
		}
		else
		{
			route = null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (config.markerGlow() && wv != null && groundMarkers.bind())
		{
			groundMarkers.hideOverlay();
			List<WorldPoint> tiles = new ArrayList<>();
			List<Color> marks = new ArrayList<>();
			groundMarkers.markers(wv, tiles, marks);
			int[] colours = new int[marks.size()];
			for (int i = 0; i < colours.length; ++i)
			{
				colours[i] = marks.get(i).getRGB();
			}
			markerColours = colours;
			markerTiles = tiles.toArray(new WorldPoint[0]);
		}
		else
		{
			markerTiles = null;
		}
	}

	// Ground Markers' tiles, refreshed each game tick, drawn as pools of light in the composite pass.
	private GroundMarkers groundMarkers;
	private WorldPoint[] markerTiles;
	private int[] markerColours;
	private final float[] markerPacked = new float[(RtRenderer.MAX_MARKERS + 1) * 4];

	// Packs the markers on this plane: a bounding box, then tile centres with the colour's bits in w.
	private void fillMarkers()
	{
		WorldView wv = client.getTopLevelWorldView();
		WorldPoint[] tiles = markerTiles;
		int[] colours = markerColours;
		if (tiles == null || tiles.length == 0 || wv == null)
		{
			frame.markerCount = 0;
			return;
		}
		int plane = client.getPlane();
		float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		int n = 0;
		for (int i = 0; i < tiles.length && n < RtRenderer.MAX_MARKERS; ++i)
		{
			LocalPoint lp = tiles[i].getPlane() == plane ? LocalPoint.fromWorld(wv, tiles[i]) : null;
			if (lp == null)
			{
				continue;
			}
			int o = (n + 1) * 4;
			markerPacked[o] = lp.getX();
			markerPacked[o + 1] = Perspective.getTileHeight(client, lp, plane);
			markerPacked[o + 2] = lp.getY();
			markerPacked[o + 3] = Float.intBitsToFloat(colours[i] & 0xffffff);
			++n;
			minX = Math.min(minX, lp.getX());
			minZ = Math.min(minZ, lp.getY());
			maxX = Math.max(maxX, lp.getX());
			maxZ = Math.max(maxZ, lp.getY());
		}
		if (n == 0)
		{
			frame.markerCount = 0;
			return;
		}
		markerPacked[0] = minX;
		markerPacked[1] = minZ;
		markerPacked[2] = maxX;
		markerPacked[3] = maxZ;
		renderer.setMarkers(markerPacked, (n + 1) * 4);
		frame.markerCount = n;
		frame.markerStrength = 1.5f * config.markerGlowStrength() / 100f;
	}

	// Which highlight colour each NPC wears this frame, as an index into the frame's palette; the
	// colours come from every plugin that highlights NPCs through the client's overlay service.
	private Field highlightedNpcsField;
	private final Map<NPC, Integer> npcHighlight = new HashMap<>();

	@SuppressWarnings("unchecked")
	private void fillHighlights()
	{
		npcHighlight.clear();
		frame.rimStrength = config.npcGlow() / 100f;
		if (frame.rimStrength <= 0f)
		{
			return;
		}
		Map<NPC, HighlightedNpc> highlighted;
		try
		{
			if (highlightedNpcsField == null)
			{
				highlightedNpcsField = NpcOverlayService.class.getDeclaredField("highlightedNpcs");
				highlightedNpcsField.setAccessible(true);
			}
			highlighted = (Map<NPC, HighlightedNpc>) highlightedNpcsField.get(npcOverlayService);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("NPC highlights unreadable", e);
		}
		Map<Integer, Integer> slots = new HashMap<>();
		for (Map.Entry<NPC, HighlightedNpc> entry : highlighted.entrySet())
		{
			Color colour = entry.getValue().getHighlightColor();
			if (colour == null)
			{
				continue;
			}
			int rgb = colour.getRGB() & 0xffffff;
			Integer slot = slots.get(rgb);
			if (slot == null)
			{
				if (slots.size() >= 15)
				{
					continue;
				}
				slot = slots.size() + 1;
				slots.put(rgb, slot);
				int o = slot * 4;
				frame.highlightColours[o] = (float) Math.pow(colour.getRed() / 255.0, 2.2);
				frame.highlightColours[o + 1] = (float) Math.pow(colour.getGreen() / 255.0, 2.2);
				frame.highlightColours[o + 2] = (float) Math.pow(colour.getBlue() / 255.0, 2.2);
				frame.highlightColours[o + 3] = 1f;
			}
			npcHighlight.put(entry.getKey(), slot);
		}
	}

	// Packs the route for the composite pass: a bounding box, then tile centres with their distance
	// along the route in w. Tiles off this plane or outside the scene break the ribbon, marked by an
	// entry with a negative w; the pulses run on across the break as if the route were unbroken.
	private void fillGuide()
	{
		WorldView wv = client.getTopLevelWorldView();
		WorldPoint[] tiles = route;
		if (tiles == null || tiles.length < 2 || wv == null)
		{
			frame.guideCount = 0;
			return;
		}
		int plane = client.getPlane();
		float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		float along = 0f, lastX = 0f, lastY = 0f, lastZ = 0f;
		boolean gap = true;
		int n = 0;
		for (WorldPoint tile : tiles)
		{
			LocalPoint lp = tile.getPlane() == plane ? LocalPoint.fromWorld(wv, tile) : null;
			if (lp == null)
			{
				gap = true;
				continue;
			}
			if (gap && n > 0)
			{
				if (n >= RtRenderer.MAX_GUIDE_POINTS)
				{
					break;
				}
				guidePacked[(n + 1) * 4 + 3] = -1f;
				++n;
			}
			if (n >= RtRenderer.MAX_GUIDE_POINTS)
			{
				break;
			}
			float x = lp.getX();
			float y = Perspective.getTileHeight(client, lp, plane);
			float z = lp.getY();
			if (!gap)
			{
				along += (float) Math.sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ));
			}
			int o = (n + 1) * 4;
			guidePacked[o] = x;
			guidePacked[o + 1] = y;
			guidePacked[o + 2] = z;
			guidePacked[o + 3] = along;
			++n;
			minX = Math.min(minX, x);
			minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x);
			maxZ = Math.max(maxZ, z);
			lastX = x;
			lastY = y;
			lastZ = z;
			gap = false;
		}
		if (n < 2)
		{
			frame.guideCount = 0;
			return;
		}
		guidePacked[0] = minX;
		guidePacked[1] = minZ;
		guidePacked[2] = maxX;
		guidePacked[3] = maxZ;
		renderer.setGuide(guidePacked, (n + 1) * 4);
		frame.guideCount = n;
		Color colour = config.pathGlowColour();
		float strength = 2f * config.pathGlowStrength() / 100f;
		frame.guideR = (float) Math.pow(colour.getRed() / 255.0, 2.2) * strength;
		frame.guideG = (float) Math.pow(colour.getGreen() / 255.0, 2.2) * strength;
		frame.guideB = (float) Math.pow(colour.getBlue() / 255.0, 2.2) * strength;
	}

	// Smoke sources: the fires drawn this frame, joined by the scene's chimneys at upload.
	private static final int MAX_PLUME_SOURCES = 64;
	private final float[] plumeSources = new float[MAX_PLUME_SOURCES * 4];
	private int plumeSourceCount;
	private final float[] plumePacked = new float[RtRenderer.MAX_PLUMES * 4];

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

	// The torch the character can be shown carrying: the lit torch item in the weapon slot, with
	// 117 HD's wall torch light following the flame of its model.
	private static final int HELD_TORCH = ItemID.TORCH_LIT + PlayerComposition.ITEM_OFFSET;
	private static final LightDefinition HELD_TORCH_LIGHT = heldTorchLight();
	private Integer heldTorchOriginal;
	private boolean torchCarried;
	private int torchFlameFaces;
	private float torchX, torchTop, torchZ;

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

	// The server's appearance updates put the real weapon back, so the swap is redone each frame.
	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		torchCarried = config.heldTorch();
		torchFlameFaces = 0;
		fillHighlights();
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

	private void restoreWeapon()
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

	// Uploads this frame's local lights: the scene's fixed and object lights plus those
	// following NPCs and the carried torch, nearest first.
	private void fillLights()
	{
		LoadedScene top = scenes.get(WorldView.TOPLEVEL);
		WorldView wv = client.getTopLevelWorldView();
		if (!config.localLights() || top == null || top.lights == null || wv == null)
		{
			frame.lightCount = 0;
			return;
		}
		carryTorch(top.lights);
		int count = top.lights.pack(wv.npcs(), client.getProjectiles(), wv.getGraphicsObjects(), client.getGameCycle(), lightLibrary(),
			(lp, plane) -> Perspective.getTileHeight(client, lp, plane),
			frame.cameraX, frame.cameraY, frame.cameraZ, frame.timeSeconds, config.lightRange() / 100f);
		renderer.setLights(top.lights.packed(), count);
		frame.lightCount = count;
		// 117 HD's strengths are tuned for its light units, which run brighter than ours.
		frame.lightStrength = config.lightStrength() / 100f * 0.35f;
	}
	private static final WeatherState NO_WEATHER = new WeatherState();
	private final WeatherState weatherNow = new WeatherState();
	private WeatherState weatherTarget = NO_WEATHER;
	private float wetness, snowCover, flash;
	private float weatherDt;
	private long lastWeatherNanos;
	private final Random lightningRandom = new Random();
	private volatile float[] skyHorizon;

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
	private volatile double skyboxSunElevation = Double.NaN;
	private static final class LoadedScene
	{
		final Scene scene;
		final StaticScene built;
		final StaticSceneBuilder.WaterBed waterBed;
		/** Lights placed in the scene; null for nested world views. */
		final SceneLights lights;
		/** Zones whose foliage was drawn swayed last frame. */
		boolean[] swayed;
		/** Rain runoff over the ground; null for nested world views. */
		WaterSim water;
		int[][][] terrainLight;

		LoadedScene(Scene scene, StaticScene built, int[][][] terrainLight, StaticSceneBuilder.WaterBed waterBed, SceneLights lights)
		{
			this.scene = scene;
			this.built = built;
			this.terrainLight = terrainLight;
			this.waterBed = waterBed;
			this.lights = lights;
		}
	}

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

	@Provides
	RltxConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RltxConfig.class);
	}

	@Override
	protected void startUp()
	{
		shortestPath = new ShortestPath(pluginManager, overlayManager);
		groundMarkers = new GroundMarkers(pluginManager, overlayManager);
		keyManager.registerKeyListener(photoModeKey);
		keyManager.registerKeyListener(freeCameraKey);
		keyManager.registerKeyListener(flightKeys);
		mouseManager.registerMouseListener(photoButtons);
		mouseManager.registerMouseListener(freeLook);
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
				float[] materials = Materials.table(gson);
				GroundTextures.applyMaterials(materials);
				renderer.setMaterials(materials);
				compositor.importSemaphores(renderer.semaphoreVkDoneHandle(), renderer.semaphoreGlDoneHandle());

				client.setDrawCallbacks(this);
				// UNLIT_FACE_COLORS is deliberately absent: with it set from client start, actors stop
				// being handed to drawDynamic. Face colours fall back to the client's lit colours.
				client.setGpuFlags(DrawCallbacks.GPU | DrawCallbacks.ZBUF | DrawCallbacks.NORMALS | DrawCallbacks.RENDER_THREADS(0));
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
		keyManager.unregisterKeyListener(photoModeKey);
		keyManager.unregisterKeyListener(freeCameraKey);
		keyManager.unregisterKeyListener(flightKeys);
		mouseManager.unregisterMouseListener(photoButtons);
		mouseManager.unregisterMouseListener(freeLook);
		chromeHidden = false;
		freeCamera = false;
		torchCarried = false;
		route = null;
		markerTiles = null;
		heldKeys.clear();
		clientThread.invoke(() ->
		{
			restoreWeapon();
			shortestPath.restoreTileOverlay();
			groundMarkers.restoreOverlay();
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
			pendingScenes.clear();
			scenes.clear();
			subTransforms.clear();
			dirtyZones.clear();
			staticDirty = false;
			frameActive = false;
			sceneFramePending = false;
			glSignalPending = false;
			// Everything the new renderer must be given again on the next start.
			gameTexturesUploaded = false;
			skyboxLoaded = false;
			requestedSkybox = null;
			skyHorizon = null;
			skyboxSunAzimuth = Double.NaN;
			skyboxSunElevation = Double.NaN;
			patternSampled = false;
			runoffUploaded = false;
			palette = null;
			lastWeatherNanos = 0;
			autoExposureLevel = 1f;
			wetness = 0f;
			snowCover = 0f;
			flash = 0f;

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
		if ("heldTorch".equals(event.getKey()) && !config.heldTorch())
		{
			clientThread.invoke(this::restoreWeapon);
		}
		if ("pathGlow".equals(event.getKey()) && !config.pathGlow())
		{
			route = null;
			clientThread.invoke(shortestPath::restoreTileOverlay);
		}
		if ("markerGlow".equals(event.getKey()) && !config.markerGlow())
		{
			markerTiles = null;
			clientThread.invoke(groundMarkers::restoreOverlay);
		}
		if (renderer == null)
		{
			return;
		}
		if ("skybox".equals(event.getKey()) || "skyboxDirectory".equals(event.getKey()))
		{
			// The next frame resolves the choice against the time of day and reloads.
			requestedSkybox = null;
		}
		if ("unlitColours".equals(event.getKey()) || "treeScale".equals(event.getKey()))
		{
			staticDirty = true;
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
		if (config.sunMode() != RltxConfig.SunMode.MANUAL)
		{
			long now = System.currentTimeMillis() + (config.sunMode() == RltxConfig.SunMode.REAL_TIME_SET ? config.timeOffset() * 3_600_000L : 0L);
			SolarPosition sun = SolarPosition.compute(now, latitude(), longitude());
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

	private Palette palette()
	{
		Palette p = palette;
		boolean undo = config.unlitColours();
		if (p == null || p.brightness() != client.getTextureProvider().getBrightness() || p.undoShading != undo)
		{
			p = new Palette(client.getTextureProvider(), undo);
			palette = p;
		}
		return p;
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		long start = System.nanoTime();
		Palette p = palette();
		classifyFoliage(StaticSceneBuilder.gameObjectIds(scene));
		StaticScene built = StaticSceneBuilder.build(scene, renderCallbackManager, p, this::foliageKind, config.treeScale() / 100f, this::hasLight);
		log.debug("Built static scene {}: {} faces in {} ms", scene.getWorldViewId(), built.totalFaces(), (System.nanoTime() - start) / 1_000_000);
		SceneLights lights = null;
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			lights = new SceneLights(RtRenderer.MAX_LIGHTS);
			lights.collect(scene, lightLibrary());
		}
		pendingScenes.put(scene.getWorldViewId(), new LoadedScene(scene, built, StaticSceneBuilder.terrainLight(scene, p), StaticSceneBuilder.waterBed(scene), lights));
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
			renderer.setMistGrid(StaticSceneBuilder.mistGrid(scene, this::isMisty));
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
				renderer.setStaticSet(e.getKey(), StaticSceneBuilder.build(loaded.scene, renderCallbackManager, p, this::foliageKind, config.treeScale() / 100f, this::hasLight), subTransforms.get(e.getKey()));
			}
			return;
		}
		for (long key : dirtyZones)
		{
			int id = (int) (key >> 32);
			int zx = (int) (key >> 16) & 0xffff;
			int zz = (int) key & 0xffff;
			LoadedScene loaded = scenes.get(id);
			if (loaded == null)
			{
				continue;
			}
			StaticScene.Zone zone = StaticSceneBuilder.buildZone(loaded.scene, zx, zz, renderCallbackManager, palette(), loaded.terrainLight, loaded.waterBed, this::foliageKind, config.treeScale() / 100f, this::hasLight);
			if (!renderer.updateZone(id, zx, zz, zone))
			{
				renderer.setStaticSet(id, StaticSceneBuilder.build(loaded.scene, renderCallbackManager, palette(), this::foliageKind, config.treeScale() / 100f, this::hasLight), subTransforms.get(id));
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

		renderer.beginFrame();
		dynamic.clear();
		dynamicTranslucent.clear();

		clientCamX = cameraX;
		clientCamY = cameraY;
		clientCamZ = cameraZ;
		clientPitch = cameraPitch;
		clientYaw = cameraYaw;
		if (freeCamera)
		{
			flyFreeCamera();
			cameraX = freeX;
			cameraY = freeY;
			cameraZ = freeZ;
			cameraPitch = freePitch;
			cameraYaw = freeYaw;
		}
		frame.cameraX = cameraX;
		frame.cameraY = cameraY;
		frame.cameraZ = cameraZ;
		frame.zoom = client.getScale();
		CameraMath.inverseRotation(cameraPitch, cameraYaw, frame.inverseRotation);
		CameraMath.forwardRotation(cameraPitch, cameraYaw, frame.forwardRotation);
		if (config.photoTiltEnabled() && config.photoTilt() != 0 && !freeCamera)
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
		boolean torch = torchCarried && renderable == client.getLocalPlayer();
		framePusher.flames = torch || tileObject != null && hasLight(tileObject.getId())
			|| renderable instanceof Projectile && lightLibrary().byProjectile.containsKey(((Projectile) renderable).getId())
			|| renderable instanceof GraphicsObject && lightLibrary().byGraphicsObject.containsKey(((GraphicsObject) renderable).getId());
		framePusher.highlight = renderable instanceof NPC ? npcHighlight.getOrDefault((NPC) renderable, 0) : 0;
		framePusher.push(model, orientation, x, y, z, transform, palette(), dynamic, dynamicTranslucent);
		framePusher.flames = false;
		framePusher.highlight = 0;
		if (torch)
		{
			torchFlameFaces = framePusher.flameFaces;
			torchX = framePusher.flameX;
			torchTop = framePusher.flameTop;
			torchZ = framePusher.flameZ;
		}
		if (tileObject != null && framePusher.flameFaces > 0 && plumeSourceCount < MAX_PLUME_SOURCES && foliageKind(tileObject.getId()) == 4)
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
		framePusher.push(model, actor.getCurrentOrientation(), lp.getX(), height, lp.getY(), null, palette(), dynamic, dynamicTranslucent);
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
		if (renderer.ensureOutput(width, height))
		{
			compositor.importSceneImage(renderer.outputHandle(), renderer.outputAllocationSize(), width, height);
		}

		fillLighting();
		frame.pattern = false;
		long start = System.nanoTime();
		addOffscreenActors();
		pushFoliage();
		fillLights();
		fillGuide();
		fillMarkers();
		fillPlumes();
		fillRunoff();
		if (burstPending)
		{
			burstPending = false;
			burst();
		}
		else
		{
			renderer.submit(frame, dynamic, dynamicTranslucent, glSignalPending, true);
		}
		motion.endFrame();
		statSubmitNanos += System.nanoTime() - start;
		glSignalPending = false;
		sceneFramePending = true;

		++statFrames;
		if (start - statInfoReport > 30_000_000_000L)
		{
			// Frame timing at info level so the launcher's console log shows it without --debug.
			log.info("GPU {} ms per frame, {} dynamic faces, {} local lights, {} frames in the last {} s",
				String.format("%.1f", renderer.lastGpuMillis()), dynamic.faces() + dynamicTranslucent.faces(), frame.lightCount,
				statFrames, (start - statInfoReport) / 1_000_000_000L);
			statInfoReport = start;
		}
		if (start - statLastReport > 5_000_000_000L)
		{
			log.debug("frames={} dynamicCalls={} tempCalls={} droppedInactive={} droppedSubScene={} dynamicFaces={} submitAvgMs={} gpuMs={} offscreenActors={}",
				statFrames, statDynamicCalls, statTempCalls, statInactive, statSubScene, dynamic.faces() + dynamicTranslucent.faces(),
				String.format("%.2f", statSubmitNanos / 1_000_000.0 / Math.max(statFrames, 1)), String.format("%.2f", renderer.lastGpuMillis()),
				statOffscreen);
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
			statOffscreen = 0;
			statSubScene = 0;
			statSubmitNanos = 0;
		}
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
				weatherTarget = WeatherState.preset(config.weatherPreset());
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
		frame.fireflies = config.fireflies();
		frame.dustMotes = config.dustMotes();
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

	// The analytic sky's colour at the horizon, matching proceduralSky() in trace.comp, for the
	// fog and distance fade to meet.
	private float[] proceduralHorizon()
	{
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

	private float autoExposureLevel = 1f;

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

	private void fillLighting()
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
		frame.aperture = config.aperture();
		frame.focusDistance = focusDistance();
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

	// The client decodes its textures lazily; once every one is available they are packed into
	// one array and uploaded. Brightness is forced to 1 so the gamma is applied only by us.
	private void ensureGameTextures()
	{
		if (gameTexturesUploaded || !(config.textures() || config.terrainTextures()))
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
		if (textures.length > GroundTextures.BASE)
		{
			throw new IllegalStateException("The client has " + textures.length + " textures; ground detail layers start at " + GroundTextures.BASE);
		}
		int layers = GroundTextures.BASE + GroundTextures.layerCount();
		ByteBuffer packed = MemoryUtil.memCalloc(layers * size * size * 4);
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
			GroundTextures.pack(packed, size);
			renderer.setTextureArray(layers, size, packed);
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
				compositor.importSceneImage(renderer.outputHandle(), renderer.outputAllocationSize(), canvasWidth, canvasHeight);
			}
			frame.pattern = true;
			renderer.submit(frame, empty, empty, glSignalPending, true);
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

		if (!chromeHidden)
		{
			compositor.drawUi(overlayColor, 0, 0, scaled(dpi.getScaleX(), targetWidth), scaled(dpi.getScaleY(), targetHeight));
		}
		drawManager.processDrawComplete(this::screenshot);

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
