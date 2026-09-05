package rltx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import org.junit.Test;
import rltx.sky.LunarPosition;
import rltx.sky.Sidereal;

public class SkyPositionTest
{
	private static long utc(int year, int month, int day, int hour, int minute)
	{
		return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
	}

	@Test
	public void polarisStandsNorthAtTheLatitude()
	{
		double latitude = 51.5;
		float[] d = Sidereal.worldDirection(utc(2026, 3, 21, 22, 0), latitude, -0.1, 37.9546, 89.2641);
		// Scene x east, y down, z north; Polaris sits three quarters of a degree from the pole.
		assertEquals(0.0, d[0], 0.02);
		assertEquals(-Math.sin(Math.toRadians(latitude)), d[1], 0.02);
		assertEquals(Math.cos(Math.toRadians(latitude)), d[2], 0.02);
	}

	@Test
	public void rotationAgreesWithDirection()
	{
		long when = utc(2026, 9, 5, 3, 0);
		float[] columns = new float[9];
		Sidereal.rotation(when, 40.0, -74.0, columns);
		double ra = Math.toRadians(88.79), dec = Math.toRadians(7.41);
		double[] e = {Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)};
		float[] direct = Sidereal.worldDirection(when, 40.0, -74.0, 88.79, 7.41);
		for (int i = 0; i < 3; ++i)
		{
			double viaColumns = columns[i] * e[0] + columns[3 + i] * e[1] + columns[6 + i] * e[2];
			assertEquals(direct[i], viaColumns, 1e-5);
		}
	}

	@Test
	public void moonPhasesOnTheAlmanacDates()
	{
		assertTrue(LunarPosition.compute(utc(2025, 1, 13, 22, 27)).illuminatedFraction > 0.98);
		assertTrue(LunarPosition.compute(utc(2025, 1, 29, 12, 36)).illuminatedFraction < 0.02);
		double quarter = LunarPosition.compute(utc(2025, 1, 21, 20, 31)).illuminatedFraction;
		assertTrue(quarter > 0.4 && quarter < 0.6);
	}
}
