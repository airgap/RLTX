package rltx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.List;
import org.junit.Test;
import rltx.scene.lights.LightDefinition;
import rltx.scene.lights.LightLibrary;

public class LightLibraryTest
{
	@Test
	public void bundledLightsParseAndResolve()
	{
		LightLibrary library = LightLibrary.load(new Gson());
		assertTrue(library.fixed.size() > 1000);
		assertTrue(library.byObject.size() > 1000);
		assertTrue(library.byNpc.size() > 300);
		for (LightDefinition def : library.fixed)
		{
			float[] rgb = def.rgb();
			assertTrue(rgb[0] >= 0f && rgb[0] <= 1f);
			assertTrue(def.strength > 0f && def.radius > 0f);
		}
	}

	@Test
	public void placementFollowsAlignment()
	{
		LightDefinition def = new LightDefinition();
		def.alignment = LightDefinition.Alignment.CENTER;
		float[] out = new float[3];
		def.placement(512, 1, 1, out);
		assertEquals(0f, out[0], 0f);
		assertEquals(0f, out[2], 0f);

		def.alignment = LightDefinition.Alignment.NORTH;
		def.placement(0, 2, 2, out);
		assertEquals(0f, out[0], 1e-3);
		assertEquals(128f, out[2], 1e-3);

		def.alignment = LightDefinition.Alignment.CUSTOM;
		def.offset = new float[]{0f, 120f, 0f};
		def.placement(0, 1, 1, out);
		assertEquals(120f, out[1], 0f);
		assertFalse(Float.isNaN(out[0]));
	}

	@Test
	public void hexColoursParse()
	{
		LightLibrary library = LightLibrary.load(new Gson());
		boolean sawHex = false;
		for (List<LightDefinition> defs : library.byNpc.values())
		{
			for (LightDefinition def : defs)
			{
				if (def.color != null && def.color.isJsonPrimitive())
				{
					float[] rgb = def.rgb();
					assertTrue(rgb[0] >= 0f && rgb[0] <= 1f && rgb[1] >= 0f && rgb[1] <= 1f && rgb[2] >= 0f && rgb[2] <= 1f);
					sawHex = true;
				}
			}
		}
		assertTrue(sawHex);
	}
}
