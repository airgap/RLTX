package rltx;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.client.RuneLite;

/**
 * Settings that belong to places: each rule names map regions and the settings that differ
 * there. Entering a rule's region applies its settings over the live ones, remembering what
 * they replaced; leaving puts those back.
 */
final class AreaRules
{
	static final File FILE = new File(RuneLite.RUNELITE_DIR, "rltx/areas.json");
	private static final Type LIST = new TypeToken<List<Rule>>()
	{
	}.getType();

	static final class Rule
	{
		String name;
		List<Integer> regions = new ArrayList<>();
		Map<String, String> overrides;

		@Override
		public String toString()
		{
			return name + "  " + regions;
		}
	}

	private final Presets presets;
	private final Gson gson;
	private final List<Rule> rules = new ArrayList<>();
	private Rule active;
	private Map<String, String> replaced;

	AreaRules(Presets presets, Gson gson)
	{
		this.presets = presets;
		this.gson = gson;
	}

	synchronized void load() throws IOException
	{
		rules.clear();
		if (FILE.isFile())
		{
			try (Reader in = Files.newBufferedReader(FILE.toPath(), StandardCharsets.UTF_8))
			{
				List<Rule> loaded = gson.fromJson(in, LIST);
				if (loaded != null)
				{
					rules.addAll(loaded);
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
			gson.toJson(rules, LIST, out);
		}
	}

	synchronized List<Rule> rules()
	{
		return new ArrayList<>(rules);
	}

	/** Adds a rule, replacing any of the same name. */
	synchronized void put(Rule rule)
	{
		rules.removeIf(r -> r.name.equals(rule.name));
		rules.add(rule);
	}

	synchronized void remove(String name)
	{
		rules.removeIf(r -> r.name.equals(name));
	}

	/**
	 * Called with the character's region each game tick. Returns a message when the area in
	 * force changed, or null.
	 */
	synchronized String tick(int region, boolean enabled)
	{
		Rule match = null;
		if (enabled)
		{
			for (Rule rule : rules)
			{
				if (rule.regions.contains(region) && rule.overrides != null)
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

	synchronized Rule active()
	{
		return active;
	}
}
