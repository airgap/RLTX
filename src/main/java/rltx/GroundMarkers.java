package rltx;

import com.google.common.collect.ListMultimap;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.groundmarkers.GroundMarkerPlugin;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * The Ground Markers plugin's tiles, read through its package-private accessor and marker
 * class, and its scene overlay, kept off the screen while RLTX draws the markers itself.
 */
@Slf4j
final class GroundMarkers
{
	private final PluginManager pluginManager;
	private final OverlayManager overlayManager;
	private GroundMarkerPlugin plugin;
	private boolean unusable;
	private Method getPoints;
	private Method getWorldPoint;
	private Method getColor;
	private Overlay overlay;

	GroundMarkers(PluginManager pluginManager, OverlayManager overlayManager)
	{
		this.pluginManager = pluginManager;
		this.overlayManager = overlayManager;
	}

	/** Finds the plugin and binds to its internals; false when it is absent or has changed shape. */
	boolean bind()
	{
		Collection<Plugin> plugins = pluginManager.getPlugins();
		if (plugin != null && plugins.contains(plugin))
		{
			return !unusable;
		}
		plugin = null;
		overlay = null;
		unusable = false;
		for (Plugin candidate : plugins)
		{
			if (candidate instanceof GroundMarkerPlugin)
			{
				plugin = (GroundMarkerPlugin) candidate;
				break;
			}
		}
		if (plugin == null)
		{
			return false;
		}
		try
		{
			getPoints = GroundMarkerPlugin.class.getDeclaredMethod("getPoints");
			getPoints.setAccessible(true);
			Class<?> marker = Class.forName("net.runelite.client.plugins.groundmarkers.ColorTileMarker");
			getWorldPoint = marker.getDeclaredMethod("getWorldPoint");
			getWorldPoint.setAccessible(true);
			getColor = marker.getDeclaredMethod("getColor");
			getColor.setAccessible(true);
			Field field = GroundMarkerPlugin.class.getDeclaredField("overlay");
			field.setAccessible(true);
			overlay = (Overlay) field.get(plugin);
		}
		catch (ReflectiveOperationException e)
		{
			// A RuneLite newer than this build knows: leave the plugin to draw its own markers.
			unusable = true;
			log.warn("Ground Markers is not in the shape RLTX knows; its markers are left to it", e);
		}
		return !unusable;
	}

	/** Appends the markers placed in the given world view to the lists, a tile and its colour each. */
	@SuppressWarnings("unchecked")
	void markers(WorldView wv, List<WorldPoint> tiles, List<Color> colours)
	{
		try
		{
			for (Object mark : ((ListMultimap<WorldView, ?>) getPoints.invoke(plugin)).get(wv))
			{
				tiles.add((WorldPoint) getWorldPoint.invoke(mark));
				colours.add((Color) getColor.invoke(mark));
			}
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("Ground Markers unreadable", e);
		}
	}

	/** Takes the plugin's own tile drawing off the screen; the plugin puts it back whenever it restarts. */
	void hideOverlay()
	{
		if (overlay != null && overlayManager.anyMatch(o -> o == overlay))
		{
			overlayManager.remove(overlay);
		}
	}

	/** Gives the tile drawing back, if the plugin is still running. */
	void restoreOverlay()
	{
		if (overlay != null && pluginManager.isPluginActive(plugin) && !overlayManager.anyMatch(o -> o == overlay))
		{
			overlayManager.add(overlay);
		}
	}
}
