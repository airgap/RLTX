package rltx;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * The Shortest Path plugin's route, read through reflection since the plugin offers no API, and
 * its tile overlay, kept off the screen while RLTX draws the route itself.
 */
@Slf4j
final class ShortestPath
{
	private static final String PLUGIN_CLASS = "shortestpath.ShortestPathPlugin";

	private final PluginManager pluginManager;
	private final OverlayManager overlayManager;
	private Plugin plugin;
	private boolean unusable;
	private Field pathfinder;
	private Method getPath;
	private Method isDone;
	private Method packedPosition;
	private Method unpackX;
	private Method unpackY;
	private Method unpackPlane;
	private Overlay tileOverlay;

	ShortestPath(PluginManager pluginManager, OverlayManager overlayManager)
	{
		this.pluginManager = pluginManager;
		this.overlayManager = overlayManager;
	}

	/** Finds the plugin among those loaded and binds to its classes; false when it is absent or has changed shape. */
	boolean bind()
	{
		Collection<Plugin> plugins = pluginManager.getPlugins();
		if (plugin != null && plugins.contains(plugin))
		{
			return !unusable;
		}
		plugin = null;
		tileOverlay = null;
		unusable = false;
		for (Plugin candidate : plugins)
		{
			if (PLUGIN_CLASS.equals(candidate.getClass().getName()))
			{
				plugin = candidate;
				break;
			}
		}
		if (plugin == null)
		{
			return false;
		}
		try
		{
			ClassLoader loader = plugin.getClass().getClassLoader();
			pathfinder = plugin.getClass().getDeclaredField("pathfinder");
			pathfinder.setAccessible(true);
			Class<?> pathfinderClass = loader.loadClass("shortestpath.pathfinder.Pathfinder");
			getPath = pathfinderClass.getMethod("getPath");
			isDone = pathfinderClass.getMethod("isDone");
			packedPosition = loader.loadClass("shortestpath.pathfinder.PathStep").getMethod("getPackedPosition");
			Class<?> util = loader.loadClass("shortestpath.WorldPointUtil");
			unpackX = util.getMethod("unpackWorldX", int.class);
			unpackY = util.getMethod("unpackWorldY", int.class);
			unpackPlane = util.getMethod("unpackWorldPlane", int.class);
			Field overlay = plugin.getClass().getDeclaredField("pathOverlay");
			overlay.setAccessible(true);
			tileOverlay = (Overlay) overlay.get(plugin);
		}
		catch (ReflectiveOperationException e)
		{
			// A newer Shortest Path with different internals: leave it be rather than break it.
			unusable = true;
			log.warn("Shortest Path is installed but not in the shape RLTX knows; its route is left to it", e);
		}
		return !unusable;
	}

	/** The route's tiles in order, or null while there is none or it is still being computed. */
	WorldPoint[] route()
	{
		try
		{
			Object finder = pathfinder.get(plugin);
			if (finder == null || !(boolean) isDone.invoke(finder))
			{
				return null;
			}
			List<?> steps = (List<?>) getPath.invoke(finder);
			WorldPoint[] route = new WorldPoint[steps.size()];
			for (int i = 0; i < route.length; ++i)
			{
				int packed = (int) packedPosition.invoke(steps.get(i));
				route[i] = new WorldPoint((int) unpackX.invoke(null, packed), (int) unpackY.invoke(null, packed), (int) unpackPlane.invoke(null, packed));
			}
			return route;
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("Shortest Path route unreadable", e);
		}
	}

	/** Takes the plugin's own tile drawing off the screen; the plugin puts it back whenever it restarts. */
	void hideTileOverlay()
	{
		if (tileOverlay != null && overlayManager.anyMatch(o -> o == tileOverlay))
		{
			overlayManager.remove(tileOverlay);
		}
	}

	/** Gives the tile drawing back, if the plugin is still running. */
	void restoreTileOverlay()
	{
		if (tileOverlay != null && pluginManager.isPluginActive(plugin) && !overlayManager.anyMatch(o -> o == tileOverlay))
		{
			overlayManager.add(tileOverlay);
		}
	}
}
