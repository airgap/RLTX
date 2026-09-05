package rltx;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;

/**
 * Settings that belong to places. Each rule bounds an area by world polygons, or by map regions
 * where no polygon is given, and holds the settings that differ there. Standing inside applies
 * them over the live ones, remembering what they replaced; leaving puts those back. Rules ship
 * with the plugin as resources and the user's own, kept in the RuneLite folder, override them
 * by name.
 */
final class AreaRules
{
	static final File FILE = new File(RuneLite.RUNELITE_DIR, "rltx/areas.json");
	static final String BUNDLED = "/rltx/areas/";
	private static final Type LIST = new TypeToken<List<Rule>>()
	{
	}.getType();

	static final class Rule
	{
		String name;
		/** World polygons: the plane, -1 for any, then the corners' x and y in order. */
		List<int[]> polygons = new ArrayList<>();
		List<Integer> regions = new ArrayList<>();
		Map<String, String> overrides = new LinkedHashMap<>();
		/** A user rule that only switches a bundled rule of the same name off. */
		boolean disabled;
		transient boolean bundled;

		boolean contains(WorldPoint p)
		{
			for (int[] polygon : polygons)
			{
				if ((polygon[0] < 0 || p.getPlane() == polygon[0]) && inside(polygon, p.getX(), p.getY()))
				{
					return true;
				}
			}
			return polygons.isEmpty() && regions.contains(p.getRegionID());
		}

		// Even-odd test against the tile's centre, with the corners taken as tile centres too, so
		// a polygon marked by standing on its corners holds everything between them.
		private static boolean inside(int[] polygon, int x, int y)
		{
			double px = x + 0.5;
			double py = y + 0.5;
			int corners = (polygon.length - 1) / 2;
			boolean in = false;
			for (int i = 0, j = corners - 1; i < corners; j = i++)
			{
				double xi = polygon[1 + i * 2] + 0.5, yi = polygon[2 + i * 2] + 0.5;
				double xj = polygon[1 + j * 2] + 0.5, yj = polygon[2 + j * 2] + 0.5;
				if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi)
				{
					in = !in;
				}
			}
			return in;
		}

		@Override
		public String toString()
		{
			return name + (bundled ? "  (bundled)" : "") + (disabled ? "  (off)" : "");
		}
	}

	private final Presets presets;
	private final Gson gson;
	private final List<Rule> bundled = new ArrayList<>();
	private final List<Rule> user = new ArrayList<>();
	private Rule active;
	private Map<String, String> replaced;

	AreaRules(Presets presets, Gson gson)
	{
		this.presets = presets;
		this.gson = gson;
	}

	/** Reads the rules shipped as resources, listed in the index beside them, then the user's file. */
	synchronized void load() throws IOException
	{
		bundled.clear();
		user.clear();
		try (InputStream index = AreaRules.class.getResourceAsStream(BUNDLED + "index.txt"))
		{
			if (index != null)
			{
				for (String line : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\\n"))
				{
					String file = line.trim();
					if (file.isEmpty())
					{
						continue;
					}
					try (InputStream in = AreaRules.class.getResourceAsStream(BUNDLED + file))
					{
						if (in == null)
						{
							throw new IOException("Bundled area listed but missing: " + file);
						}
						Rule rule = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Rule.class);
						rule.bundled = true;
						bundled.add(rule);
					}
				}
			}
		}
		if (FILE.isFile())
		{
			try (Reader in = Files.newBufferedReader(FILE.toPath(), StandardCharsets.UTF_8))
			{
				List<Rule> loaded = gson.fromJson(in, LIST);
				if (loaded != null)
				{
					user.addAll(loaded);
				}
			}
		}
	}

	synchronized void save() throws IOException
	{
		File dir = FILE.getParentFile();
		if (!dir.isDirectory() && !dir.mkdirs())
		{
			throw new IOException("Cannot create " + dir);
		}
		try (Writer out = Files.newBufferedWriter(FILE.toPath(), StandardCharsets.UTF_8))
		{
			gson.toJson(user, LIST, out);
		}
	}

	/** Writes one rule as a resource file in the repository checkout, and lists it in the index. */
	void saveBundled(File repository, Rule rule) throws IOException
	{
		File dir = new File(repository, "src/main/resources" + BUNDLED);
		if (!dir.isDirectory() && !dir.mkdirs())
		{
			throw new IOException("Cannot create " + dir);
		}
		String file = rule.name.replaceAll("[^A-Za-z0-9 _.-]", "_").replace(' ', '-').toLowerCase() + ".json";
		Rule copy = new Rule();
		copy.name = rule.name;
		copy.polygons = rule.polygons;
		copy.regions = rule.regions;
		copy.overrides = rule.overrides;
		try (Writer out = Files.newBufferedWriter(new File(dir, file).toPath(), StandardCharsets.UTF_8))
		{
			gson.toJson(copy, Rule.class, out);
		}
		File index = new File(dir, "index.txt");
		List<String> lines = index.isFile() ? new ArrayList<>(Files.readAllLines(index.toPath(), StandardCharsets.UTF_8)) : new ArrayList<>();
		if (!lines.contains(file))
		{
			lines.add(file);
			Files.write(index.toPath(), lines, StandardCharsets.UTF_8);
		}
		synchronized (this)
		{
			bundled.removeIf(r -> r.name.equals(rule.name));
			copy.bundled = true;
			bundled.add(copy);
		}
	}

	/** Every rule in force order: the user's, then the bundled ones they do not override. */
	synchronized List<Rule> rules()
	{
		List<Rule> all = new ArrayList<>(user);
		for (Rule rule : bundled)
		{
			boolean overridden = false;
			for (Rule mine : user)
			{
				overridden |= mine.name.equals(rule.name);
			}
			if (!overridden)
			{
				all.add(rule);
			}
		}
		return all;
	}

	/** Adds a user rule, replacing any of the same name. */
	synchronized void put(Rule rule)
	{
		user.removeIf(r -> r.name.equals(rule.name));
		user.add(rule);
	}

	/** Drops a user rule; a bundled rule is switched off by a user rule that says so. */
	synchronized void remove(Rule rule)
	{
		user.removeIf(r -> r.name.equals(rule.name));
		if (rule.bundled)
		{
			Rule off = new Rule();
			off.name = rule.name;
			off.disabled = true;
			user.add(off);
		}
	}

	/**
	 * Called with the character's position each game tick. Returns a message when the area in
	 * force changed, or null.
	 */
	synchronized String tick(WorldPoint position, boolean enabled)
	{
		Rule match = null;
		if (enabled && position != null)
		{
			for (Rule rule : rules())
			{
				if (!rule.disabled && rule.overrides != null && rule.contains(position))
				{
					match = rule;
					break;
				}
			}
		}
		if (match == active)
		{
			return null;
		}
		if (active != null)
		{
			presets.apply(replaced);
			replaced = null;
		}
		active = match;
		if (match == null)
		{
			return "RLTX: area settings restored";
		}
		replaced = presets.captureKeys(match.overrides.keySet());
		presets.apply(match.overrides);
		return "RLTX: " + match.name + " settings";
	}

	/** Puts back whatever the active area replaced, for shutdown. */
	synchronized void reset()
	{
		if (active != null)
		{
			presets.apply(replaced);
			active = null;
			replaced = null;
		}
	}

	/** The repository checkout this build runs from, or null when running from a jar. */
	static File repository()
	{
		try
		{
			File dir = new File(AreaRules.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			for (int up = 0; up < 6 && dir != null; ++up)
			{
				if (new File(dir, "build.gradle").isFile() && new File(dir, "src/main/resources/rltx").isDirectory())
				{
					return dir;
				}
				dir = dir.getParentFile();
			}
		}
		catch (java.net.URISyntaxException e)
		{
			return null;
		}
		return null;
	}
}
