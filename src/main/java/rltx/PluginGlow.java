package rltx;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.OverlayManager;
import rltx.scene.GroundTextures;
import rltx.vk.FrameParams;
import rltx.vk.RtRenderer;

/**
 * What other plugins show, drawn as light by the composite pass instead of their own overlays:
 * Shortest Path's route as a ribbon, a worn trail or wisps, Ground Markers' tiles as pools, and
 * the highlight colours NPC plugins give their NPCs as rims. Also the outline of an area
 * polygon while the Areas tab is editing one.
 */
final class PluginGlow
{
	private final Client client;
	private final ClientThread clientThread;
	private final RltxConfig config;
	private final NpcOverlayService npcOverlayService;
	private final FrameParams frame;

	private final ShortestPath shortestPath;
	private WorldPoint[] route;
	private final float[] guidePacked = new float[(RtRenderer.MAX_GUIDE_POINTS + 1 + RtRenderer.MAX_WISPS) * 4];

	private final GroundMarkers groundMarkers;
	private WorldPoint[] markerTiles;
	private int[] markerColours;
	private final float[] markerPacked = new float[(RtRenderer.MAX_MARKERS + 1) * 4];
	// The polygons the Areas tab is showing, drawn on the ground as a line of white pools.
	private volatile List<int[]> previewPolygons;
	private int markerFill;
	private float markerMinX, markerMinZ, markerMaxX, markerMaxZ;

	// Which highlight colour each NPC wears this frame, as an index into the frame's palette; the
	// colours come from every plugin that highlights NPCs through the client's overlay service.
	private Field highlightedNpcsField;
	private final Map<NPC, Integer> npcHighlight = new HashMap<>();

	PluginGlow(Client client, ClientThread clientThread, RltxConfig config, PluginManager pluginManager, OverlayManager overlayManager,
		NpcOverlayService npcOverlayService, FrameParams frame)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.npcOverlayService = npcOverlayService;
		this.frame = frame;
		shortestPath = new ShortestPath(pluginManager, overlayManager);
		groundMarkers = new GroundMarkers(pluginManager, overlayManager);
	}

	void previewPolygons(List<int[]> polygons)
	{
		previewPolygons = polygons;
	}

	/** Refreshes the route and the marked tiles from their plugins, once a game tick. */
	void tick()
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

	/** The route glow was switched off: Shortest Path gets its own overlay back. */
	void disablePath()
	{
		route = null;
		clientThread.invoke(shortestPath::restoreTileOverlay);
	}

	/** The marker glow was switched off: Ground Markers gets its own overlay back. */
	void disableMarkers()
	{
		markerTiles = null;
		clientThread.invoke(groundMarkers::restoreOverlay);
	}

	void clear()
	{
		route = null;
		markerTiles = null;
	}

	/** Gives both plugins their overlays back; on the client thread. */
	void restoreOverlays()
	{
		shortestPath.restoreTileOverlay();
		groundMarkers.restoreOverlay();
	}

	// Packs the markers on this plane: a bounding box, then tile centres with the colour's bits in
	// w, followed by the tiles along the edges of any area polygon being edited.
	void fillMarkers(RtRenderer renderer, Cells cells)
	{
		WorldView wv = client.getTopLevelWorldView();
		WorldPoint[] tiles = markerTiles;
		int[] colours = markerColours;
		List<int[]> outlines = previewPolygons;
		if (wv == null || (tiles == null && outlines == null))
		{
			frame.markerCount = 0;
			return;
		}
		int plane = client.getPlane();
		markerFill = 0;
		cells.clear(Cells.MARKERS);
		markerMinX = Float.MAX_VALUE;
		markerMinZ = Float.MAX_VALUE;
		markerMaxX = -Float.MAX_VALUE;
		markerMaxZ = -Float.MAX_VALUE;
		if (tiles != null)
		{
			for (int i = 0; i < tiles.length; ++i)
			{
				addMarker(wv, plane, tiles[i], colours[i], cells);
			}
		}
		if (outlines != null)
		{
			for (int[] polygon : outlines)
			{
				int corners = (polygon.length - 1) / 2;
				for (int i = 0; i < corners; ++i)
				{
					int ax = polygon[1 + i * 2], ay = polygon[2 + i * 2];
					int bx = polygon[1 + ((i + 1) % corners) * 2], by = polygon[2 + ((i + 1) % corners) * 2];
					int steps = Math.max(Math.abs(bx - ax), Math.abs(by - ay));
					for (int s = 0; s <= steps; ++s)
					{
						int x = ax + Math.round((bx - ax) * (s / (float) Math.max(steps, 1)));
						int y = ay + Math.round((by - ay) * (s / (float) Math.max(steps, 1)));
						addMarker(wv, plane, new WorldPoint(x, y, plane), 0xffffff, cells);
					}
				}
			}
		}
		if (markerFill == 0)
		{
			frame.markerCount = 0;
			return;
		}
		markerPacked[0] = markerMinX;
		markerPacked[1] = markerMinZ;
		markerPacked[2] = markerMaxX;
		markerPacked[3] = markerMaxZ;
		renderer.setMarkers(markerPacked, (markerFill + 1) * 4);
		frame.markerCount = markerFill;
		float strength = 1.5f * config.markerGlowStrength() / 100f;
		frame.markerStrength = outlines != null ? Math.max(strength, 1.5f) : strength;
	}

	private void addMarker(WorldView wv, int plane, WorldPoint tile, int rgb, Cells cells)
	{
		if (markerFill >= RtRenderer.MAX_MARKERS || tile.getPlane() != plane)
		{
			return;
		}
		LocalPoint lp = LocalPoint.fromWorld(wv, tile);
		if (lp == null)
		{
			return;
		}
		int o = (markerFill + 1) * 4;
		markerPacked[o] = lp.getX();
		markerPacked[o + 1] = Perspective.getTileHeight(client, lp, plane);
		markerPacked[o + 2] = lp.getY();
		markerPacked[o + 3] = Float.intBitsToFloat(rgb & 0xffffff);
		++markerFill;
		cells.mark(Cells.MARKERS, lp.getX() - 80f, lp.getY() - 80f, lp.getX() + 80f, lp.getY() + 80f);
		markerMinX = Math.min(markerMinX, lp.getX());
		markerMinZ = Math.min(markerMinZ, lp.getY());
		markerMaxX = Math.max(markerMaxX, lp.getX());
		markerMaxZ = Math.max(markerMaxZ, lp.getY());
	}

	// Wisps drift along the route at a walking pace, evenly spaced, each placed by walking the
	// packed polyline to its distance along; entries follow the route points in the same buffer.
	private int placeWisps(int points, float length)
	{
		if (length <= 0f)
		{
			return 0;
		}
		int wisps = Math.max(1, Math.min(RtRenderer.MAX_WISPS, (int) (length / 384f)));
		float spacing = length / wisps;
		float travelled = frame.timeSeconds * 220f;
		for (int k = 0; k < wisps; ++k)
		{
			float target = (travelled + k * spacing) % length;
			int o = (points + 1 + k) * 4;
			guidePacked[o + 3] = k;
			for (int i = 1; i < points; ++i)
			{
				int a = (i) * 4;
				int b = (i + 1) * 4;
				if (guidePacked[a + 3] < 0f || guidePacked[b + 3] < 0f || guidePacked[b + 3] < target)
				{
					continue;
				}
				float span = guidePacked[b + 3] - guidePacked[a + 3];
				float t = span > 0f ? Math.max(0f, Math.min(1f, (target - guidePacked[a + 3]) / span)) : 0f;
				guidePacked[o] = guidePacked[a] + (guidePacked[b] - guidePacked[a]) * t;
				guidePacked[o + 1] = guidePacked[a + 1] + (guidePacked[b + 1] - guidePacked[a + 1]) * t;
				guidePacked[o + 2] = guidePacked[a + 2] + (guidePacked[b + 2] - guidePacked[a + 2]) * t;
				break;
			}
		}
		return wisps;
	}

	/** The highlight palette slot an NPC wears this frame, 0 for none. */
	int highlight(NPC npc)
	{
		return npcHighlight.getOrDefault(npc, 0);
	}

	@SuppressWarnings("unchecked")
	void fillHighlights()
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
	void fillGuide(RtRenderer renderer, Cells cells)
	{
		WorldView wv = client.getTopLevelWorldView();
		WorldPoint[] tiles = route;
		cells.clear(Cells.ROUTE);
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
				cells.mark(Cells.ROUTE, Math.min(x, lastX) - 64f, Math.min(z, lastZ) - 64f, Math.max(x, lastX) + 64f, Math.max(z, lastZ) + 64f);
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
		RltxConfig.PathStyle style = config.pathStyle();
		int wisps = style == RltxConfig.PathStyle.WISPS || style == RltxConfig.PathStyle.TRAIL_WISPS ? placeWisps(n, along) : 0;
		renderer.setGuide(guidePacked, (n + 1 + wisps) * 4);
		frame.guideCount = n;
		frame.guideStyle = style.ordinal();
		frame.guideWisps = wisps;
		frame.dirtLayer = GroundTextures.Kind.DIRT.layer();
		Color colour = config.pathGlowColour();
		float strength = 2f * config.pathGlowStrength() / 100f;
		frame.guideR = (float) Math.pow(colour.getRed() / 255.0, 2.2) * strength;
		frame.guideG = (float) Math.pow(colour.getGreen() / 255.0, 2.2) * strength;
		frame.guideB = (float) Math.pow(colour.getBlue() / 255.0, 2.2) * strength;
	}
}
