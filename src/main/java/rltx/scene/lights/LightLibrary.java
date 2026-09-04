package rltx.scene.lights;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 117 HD's light definitions, bundled from its lights.json, with the object and NPC names
 * they refer to resolved to ids through the table extracted from its game values.
 */
@Slf4j
public final class LightLibrary
{
	public final List<LightDefinition> fixed = new ArrayList<>();
	public final Map<Integer, List<LightDefinition>> byObject = new HashMap<>();
	public final Map<Integer, List<LightDefinition>> byNpc = new HashMap<>();

	public static LightLibrary load(Gson gson)
	{
		LightLibrary library = new LightLibrary();
		LightDefinition[] definitions;
		JsonObject ids;
		try (Reader lights = open("/rltx/hd/lights.json"); Reader table = open("/rltx/hd/light_ids.json"))
		{
			JsonReader reader = new JsonReader(lights);
			reader.setLenient(true);
			definitions = gson.fromJson(reader, LightDefinition[].class);
			ids = gson.fromJson(table, JsonObject.class);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		JsonObject objects = ids.getAsJsonObject("objects");
		JsonObject npcs = ids.getAsJsonObject("npcs");
		int unresolved = 0;
		for (LightDefinition def : definitions)
		{
			if (def.strength <= 0f || def.radius <= 0f)
			{
				continue;
			}
			if (def.fixed())
			{
				library.fixed.add(def);
			}
			unresolved += attach(def, def.objectIds, objects, library.byObject);
			unresolved += attach(def, def.npcIds, npcs, library.byNpc);
		}
		log.info("Loaded {} light definitions: {} fixed, {} object ids, {} npc ids, {} names unresolved",
			definitions.length, library.fixed.size(), library.byObject.size(), library.byNpc.size(), unresolved);
		return library;
	}

	private static int attach(LightDefinition def, List<String> names, JsonObject table, Map<Integer, List<LightDefinition>> into)
	{
		if (names == null)
		{
			return 0;
		}
		int unresolved = 0;
		for (String name : names)
		{
			int id;
			if (table.has(name))
			{
				id = table.get(name).getAsInt();
			}
			else if (name.chars().allMatch(Character::isDigit))
			{
				id = Integer.parseInt(name);
			}
			else
			{
				++unresolved;
				continue;
			}
			into.computeIfAbsent(id, k -> new ArrayList<>()).add(def);
		}
		return unresolved;
	}

	private static Reader open(String resource) throws IOException
	{
		var stream = LightLibrary.class.getResourceAsStream(resource);
		if (stream == null)
		{
			throw new IOException("Missing bundled resource " + resource);
		}
		return new InputStreamReader(stream, StandardCharsets.UTF_8);
	}
}
