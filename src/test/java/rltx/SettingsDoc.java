package rltx;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

/** Writes docs/settings.md from the config interface's annotations: one table per section. */
public class SettingsDoc
{
	public static void main(String[] args) throws IOException, ReflectiveOperationException
	{
		Map<String, String> sectionNames = new HashMap<>();
		Map<String, Integer> sectionOrder = new HashMap<>();
		for (Field field : RltxConfig.class.getDeclaredFields())
		{
			ConfigSection section = field.getAnnotation(ConfigSection.class);
			if (section != null)
			{
				sectionNames.put((String) field.get(null), section.name());
				sectionOrder.put((String) field.get(null), section.position());
			}
		}
		Map<String, List<Method>> bySection = new HashMap<>();
		for (Method method : RltxConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null && !item.hidden())
			{
				bySection.computeIfAbsent(item.section(), k -> new ArrayList<>()).add(method);
			}
		}
		List<String> sections = new ArrayList<>(bySection.keySet());
		sections.sort(Comparator.comparingInt(s -> sectionOrder.getOrDefault(s, 99)));

		StringBuilder out = new StringBuilder("# RLTX settings\n\nGenerated from the plugin's configuration by `./gradlew settingsDoc`; do not edit by hand.\n");
		for (String section : sections)
		{
			out.append("\n## ").append(sectionNames.getOrDefault(section, section)).append("\n\n| Setting | Default | Description |\n| --- | --- | --- |\n");
			List<Method> items = bySection.get(section);
			items.sort(Comparator.comparingInt(m -> m.getAnnotation(ConfigItem.class).position()));
			for (Method method : items)
			{
				ConfigItem item = method.getAnnotation(ConfigItem.class);
				out.append("| ").append(item.name()).append(" | ").append(defaultOf(method)).append(" | ").append(item.description().replace("|", "\\|")).append(" |\n");
			}
		}
		Files.write(Paths.get(args.length > 0 ? args[0] : "docs/settings.md"), out.toString().getBytes(StandardCharsets.UTF_8));
	}

	// The default as the interface's own method returns it, with the range and unit beside it.
	private static String defaultOf(Method method) throws ReflectiveOperationException
	{
		Object proxy = java.lang.reflect.Proxy.newProxyInstance(RltxConfig.class.getClassLoader(), new Class<?>[]{RltxConfig.class}, (p, m, a) ->
			java.lang.invoke.MethodHandles.lookup().unreflectSpecial(m, RltxConfig.class).bindTo(p).invokeWithArguments());
		Object value;
		try
		{
			value = method.invoke(proxy);
		}
		catch (InvocationTargetException e)
		{
			throw new ReflectiveOperationException(e.getCause());
		}
		String shown = value instanceof Keybind ? value.toString() : value instanceof java.awt.Color ? String.format("#%06x", ((java.awt.Color) value).getRGB() & 0xffffff) : String.valueOf(value);
		Units units = method.getAnnotation(Units.class);
		Range range = method.getAnnotation(Range.class);
		if (units != null)
		{
			shown += units.value();
		}
		if (range != null)
		{
			shown += " (" + range.min() + " to " + range.max() + ")";
		}
		return shown;
	}
}
