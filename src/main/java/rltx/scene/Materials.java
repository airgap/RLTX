package rltx.scene;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Surface properties per vanilla texture id from 117 HD's materials.json: specular strength
 * and gloss where it defines them, and whether the texture is unlit, which we render as
 * emissive. Entries inherit from their parent chain as 117 HD's loader does.
 */
public final class Materials
{
	public static final int TEXTURES = 256;
	/** Floats per texture in the GPU table: specular strength, gloss exponent, emissive, brightness. */
	public static final int FLOATS = 4;

	private Materials()
	{
	}

	public static float[] table(Gson gson)
	{
		JsonElement[] entries;
		try (Reader reader = new InputStreamReader(Materials.class.getResourceAsStream("/rltx/hd/materials.json"), StandardCharsets.UTF_8))
		{
			entries = gson.fromJson(reader, JsonElement[].class);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		Map<String, JsonObject> byName = new HashMap<>();
		for (JsonElement e : entries)
		{
			JsonObject o = e.getAsJsonObject();
			if (o.has("name"))
			{
				byName.put(o.get("name").getAsString(), o);
			}
		}
		float[] table = new float[TEXTURES * FLOATS];
		for (int i = 0; i < TEXTURES; ++i)
		{
			table[i * FLOATS + 3] = 1f;
		}
		for (JsonElement e : entries)
		{
			JsonObject o = e.getAsJsonObject();
			if (!o.has("vanillaTextureIndex"))
			{
				continue;
			}
			int id = o.get("vanillaTextureIndex").getAsInt();
			if (id < 0 || id >= TEXTURES)
			{
				continue;
			}
			int base = id * FLOATS;
			table[base] = resolve(o, byName, "specularStrength", 0f);
			table[base + 1] = resolve(o, byName, "specularGloss", 0f);
			table[base + 2] = resolveBoolean(o, byName, "unlit") ? 1f : 0f;
			table[base + 3] = resolve(o, byName, "brightness", 1f);
		}
		return table;
	}

	private static float resolve(JsonObject o, Map<String, JsonObject> byName, String field, float fallback)
	{
		for (JsonObject cur = o; cur != null; cur = cur.has("parent") ? byName.get(cur.get("parent").getAsString()) : null)
		{
			if (cur.has(field))
			{
				return cur.get(field).getAsFloat();
			}
		}
		return fallback;
	}

	private static boolean resolveBoolean(JsonObject o, Map<String, JsonObject> byName, String field)
	{
		for (JsonObject cur = o; cur != null; cur = cur.has("parent") ? byName.get(cur.get("parent").getAsString()) : null)
		{
			if (cur.has(field))
			{
				return cur.get(field).getAsBoolean();
			}
		}
		return false;
	}
}
