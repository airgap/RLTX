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
import java.util.Collections;
import java.util.List;
import net.runelite.client.RuneLite;

/** Cinema paths saved by name under the RuneLite folder: the keyframes as recorded. */
final class CinemaPaths
{
	static final File DIR = new File(RuneLite.RUNELITE_DIR, "rltx/paths");
	private static final Type KEYS = new TypeToken<List<double[]>>()
	{
	}.getType();

	private final Gson gson;

	CinemaPaths(Gson gson)
	{
		this.gson = gson;
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

	void save(String name, List<double[]> keys) throws IOException
	{
		if (!DIR.isDirectory() && !DIR.mkdirs())
		{
			throw new IOException("Cannot create " + DIR);
		}
		try (Writer out = Files.newBufferedWriter(file(name).toPath(), StandardCharsets.UTF_8))
		{
			gson.toJson(keys, KEYS, out);
		}
	}

	List<double[]> load(String name) throws IOException
	{
		try (Reader in = Files.newBufferedReader(file(name).toPath(), StandardCharsets.UTF_8))
		{
			return gson.fromJson(in, KEYS);
		}
	}

	void delete(String name) throws IOException
	{
		Files.deleteIfExists(file(name).toPath());
	}

	private static File file(String name)
	{
		return new File(DIR, name.replaceAll("[^A-Za-z0-9 _.-]", "_") + ".json");
	}
}
