package rltx;

import static org.junit.Assert.assertEquals;
import java.util.Collections;
import net.runelite.api.NPC;
import org.junit.Test;
import rltx.scene.lights.LightDefinition;
import rltx.scene.lights.SceneLights;

public class SceneLightsTest
{
	private static LightDefinition torch()
	{
		LightDefinition def = new LightDefinition();
		def.radius = 300f;
		def.strength = 10f;
		return def;
	}

	private static int pack(SceneLights lights)
	{
		return lights.pack(Collections.<NPC>emptyList(), null, (lp, plane) -> 0f, 0f, 0f, 0f, 0f, 1f);
	}

	@Test
	public void carriedLightIsPackedWhereItIsHeld()
	{
		SceneLights lights = new SceneLights(4);
		lights.carry(torch(), 100f, -150f, 200f);
		assertEquals(1, pack(lights));
		float[] packed = lights.packed();
		assertEquals(100f, packed[0], 0f);
		assertEquals(-150f, packed[1], 0f);
		assertEquals(200f, packed[2], 0f);
		assertEquals(300f, packed[3], 0f);
		assertEquals(10f, packed[4], 0f);
	}

	@Test
	public void carriedLightIsDroppedWhenPutDown()
	{
		SceneLights lights = new SceneLights(4);
		lights.carry(torch(), 0f, 0f, 0f);
		lights.carry(null, 0f, 0f, 0f);
		assertEquals(0, pack(lights));
	}
}
