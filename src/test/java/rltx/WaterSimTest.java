package rltx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import rltx.scene.WaterSim;

public class WaterSimTest
{
	// A 5 by 5 grid sloping down toward a hollow in the middle; heights grow downward.
	private static int[][] basin()
	{
		int[][] h = new int[5][5];
		for (int x = 0; x < 5; ++x)
		{
			for (int y = 0; y < 5; ++y)
			{
				h[x][y] = -40 * (Math.abs(x - 2) + Math.abs(y - 2));
			}
		}
		return h;
	}

	private static float depthAt(WaterSim sim, int x, int y)
	{
		return sim.packed()[(x * 5 + y) * WaterSim.FLOATS];
	}

	@Test
	public void rainGathersInTheHollow()
	{
		WaterSim sim = new WaterSim(basin());
		for (int i = 0; i < 600; ++i)
		{
			assertTrue(sim.step(1f / 60f, 1f));
		}
		assertTrue(depthAt(sim, 2, 2) > depthAt(sim, 0, 0));
		assertTrue(depthAt(sim, 2, 2) > 4f);
	}

	@Test
	public void groundDriesAfterTheRainStops()
	{
		WaterSim sim = new WaterSim(basin());
		for (int i = 0; i < 300; ++i)
		{
			sim.step(1f / 60f, 1f);
		}
		boolean wet = true;
		for (int i = 0; i < 60 * 30 && wet; ++i)
		{
			wet = sim.step(1f / 60f, 0f);
		}
		assertFalse(wet);
		assertEquals(0f, depthAt(sim, 2, 2), 0f);
	}

	@Test
	public void flowPointsDownhill()
	{
		WaterSim sim = new WaterSim(basin());
		for (int i = 0; i < 120; ++i)
		{
			sim.step(1f / 60f, 1f);
		}
		// The vertex west of the hollow should send water east, toward it.
		float flowX = sim.packed()[(1 * 5 + 2) * WaterSim.FLOATS + 1];
		assertTrue(flowX > 0f);
	}
}
