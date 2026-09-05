package rltx;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;

/**
 * Whole sets of RLTX settings as RuneLite stores them, string per key: captured from the live
 * configuration, saved as JSON files under the RuneLite folder or on the clipboard, and applied
 * back through the config manager so every listener sees the change.
 */
final class Presets
{
	static final File DIR = new File(RuneLite.RUNELITE_DIR, "rltx/presets");
	private static final Type MAP = new TypeToken<Map<String, String>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final RltxConfig config;
	private final Gson gson;
	private final Map<String, Method> items = new LinkedHashMap<>();

	Presets(ConfigManager configManager, RltxConfig config, Gson gson)
	{
		this.configManager = configManager;
		this.config = config;
		this.gson = gson;
		for (Method method : RltxConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null)
			{
				items.put(item.keyName(), method);
			}
		}
	}

	/** Every setting, defaults included, in RuneLite's stored form. */
	Map<String, String> capture()
	{
		return captureKeys(items.keySet());
	}

	Map<String, String> captureKeys(Set<String> keys)
	{
		Map<String, String> values = new LinkedHashMap<>();
		for (String key : keys)
		{
			Method method = items.get(key);
			if (method == null)
			{
				continue;
			}
			String stored = configManager.getConfiguration(RltxConfig.GROUP, key);
			if (stored == null)
			{
				try
				{
					stored = stringOf(method.invoke(config));
				}
				catch (IllegalAccessException | InvocationTargetException e)
				{
					throw new IllegalStateException("Cannot read setting " + key, e);
				}
			}
			values.put(key, stored);
		}
		return values;
	}

	/** Writes the values into the live configuration; keys this build does not know are skipped. */
	void apply(Map<String, String> values)
	{
		for (Map.Entry<String, String> entry : values.entrySet())
		{
			if (items.containsKey(entry.getKey()))
			{
				configManager.setConfiguration(RltxConfig.GROUP, entry.getKey(), entry.getValue());
			}
		}
	}

	/** The entries of {@code current} that differ from {@code base}. */
	static Map<String, String> diff(Map<String, String> base, Map<String, String> current)
	{
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : current.entrySet())
		{
			if (!entry.getValue().equals(base.get(entry.getKey())))
			{
				out.put(entry.getKey(), entry.getValue());
			}
		}
		return out;
	}

	// The forms RuneLite itself stores these types in, so files round-trip through the sidebar.
	static String stringOf(Object value)
	{
		if (value instanceof Color)
		{
			return Integer.toString(((Color) value).getRGB());
		}
		if (value instanceof Keybind)
		{
			Keybind k = (Keybind) value;
			return k.getKeyCode() + ":" + k.getModifiers();
		}
		if (value instanceof Enum)
		{
			return ((Enum<?>) value).name();
		}
		return String.valueOf(value);
	}

	List<String> names()
	{
		File[] files = DIR.listFiles((dir, name) -> name.endsWith(".json"));
		List<String> names = new ArrayList<>();
		if (files != null)
		{
			for (File file : files)
			{
				names.add(file.getName().substring(0, file.getName().length() - 5));
			}
		}
		Collections.sort(names);
		return names;
	}

	void save(String name, Map<String, String> values) throws IOException
	{
		if (!DIR.isDirectory() && !DIR.mkdirs())
		{
			throw new IOException("Cannot create " + DIR);
		}
		try (Writer out = Files.newBufferedWriter(file(name).toPath(), StandardCharsets.UTF_8))
		{
			gson.toJson(values, MAP, out);
		}
	}

	Map<String, String> load(String name) throws IOException
	{
		try (Reader in = Files.newBufferedReader(file(name).toPath(), StandardCharsets.UTF_8))
		{
			return gson.fromJson(in, MAP);
		}
	}

	void delete(String name) throws IOException
	{
		Files.deleteIfExists(file(name).toPath());
	}

	void copy(Map<String, String> values)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(gson.toJson(values, MAP)), null);
	}

	/** The clipboard's contents as a preset, or null when it holds none. */
	Map<String, String> paste() throws IOException
	{
		try
		{
			String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
			return gson.fromJson(text, MAP);
		}
		catch (UnsupportedFlavorException | JsonSyntaxException e)
		{
			return null;
		}
	}

	private static File file(String name)
	{
		return new File(DIR, name.replaceAll("[^A-Za-z0-9 _.-]", "_") + ".json");
	}
}
