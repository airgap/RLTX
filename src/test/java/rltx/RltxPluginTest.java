package rltx;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class RltxPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// Under the Jagex Launcher the client's stdout is a pipe nobody reads, and a full pipe
		// blocks every log write. The Windows stub cannot redirect it, so the JVM does.
		String console = System.getProperty("rltx.console");
		if (console != null)
		{
			PrintStream out = new PrintStream(new FileOutputStream(console), true);
			System.setOut(out);
			System.setErr(out);
		}
		// RuneLite.exe passes the launcher's arguments straight through, and there is nowhere in
		// its config.json to add our own, so developer mode is on unless asked for otherwise.
		List<String> arguments = new ArrayList<>(Arrays.asList(args));
		if (!arguments.contains("--developer-mode"))
		{
			arguments.add("--developer-mode");
		}
		ExternalPluginManager.loadBuiltin(RltxPlugin.class);
		RuneLite.main(arguments.toArray(new String[0]));
	}
}
