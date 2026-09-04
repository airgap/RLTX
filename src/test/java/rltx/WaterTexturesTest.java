package rltx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import rltx.scene.WaterTextures;
import rltx.scene.WaterType;

public class WaterTexturesTest
{
	@Test
	public void vanillaWaterTexturesFollow117HdRule()
	{
		assertEquals(WaterType.WATER, WaterType.forTexture(1));
		assertEquals(WaterType.WATER, WaterType.forTexture(24));
		assertEquals(WaterType.SWAMP_WATER_FLAT, WaterType.forTexture(25));
		assertEquals(WaterType.VANILLA_130, WaterType.forTexture(130));
		assertEquals(WaterType.VANILLA_208, WaterType.forTexture(208));
		assertNull(WaterType.forTexture(129));
		assertNull(WaterType.forTexture(31));
		assertFalse(WaterTextures.isWater(-1));
	}

	@Test
	public void encodingCarriesTextureAndType()
	{
		int field = WaterTextures.encode(25);
		assertTrue((field & WaterTextures.WATER_BIT) != 0);
		assertEquals(26, field & 0xFFFF);
		assertEquals(WaterType.SWAMP_WATER_FLAT.ordinal() + 1, (field >> 16) & 0xFF);
		assertEquals(3, WaterTextures.encode(2));
	}

	@Test
	public void tableLeavesIndexZeroEmptyAndPacksFlags()
	{
		float[] table = WaterType.table();
		assertEquals((WaterType.values().length + 1) * WaterType.FLOATS, table.length);
		for (int i = 0; i < WaterType.FLOATS; ++i)
		{
			assertEquals(0f, table[i], 0f);
		}
		int swamp = (WaterType.SWAMP_WATER_FLAT.ordinal() + 1) * WaterType.FLOATS;
		assertEquals(3f, table[swamp + 3], 0f);
		assertEquals(0.8f, table[swamp + 11], 1e-6);
	}
}
