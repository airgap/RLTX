package rltx;

import static org.junit.Assert.assertEquals;
import java.time.Instant;
import org.junit.Test;
import rltx.sky.SolarPosition;

public class SolarPositionTest
{
	private static long at(String iso)
	{
		return Instant.parse(iso).toEpochMilli();
	}

	@Test
	public void juneSolsticeNoonAtGreenwichMeridian()
	{
		// Solar noon at 0° longitude falls a couple of minutes off 12:00 UTC; the sun stands
		// at the declination's altitude, due north of the equator.
		SolarPosition sun = SolarPosition.compute(at("2024-06-20T12:02:00Z"), 0, 0);
		assertEquals(66.5, sun.elevationDegrees, 1.0);
		assertEquals(0.0, Math.min(sun.azimuthDegrees, 360 - sun.azimuthDegrees), 3.0);
	}

	@Test
	public void juneSolsticeSunriseAtEquatorIsNorthOfEast()
	{
		SolarPosition sun = SolarPosition.compute(at("2024-06-20T05:55:00Z"), 0, 0);
		assertEquals(0.0, sun.elevationDegrees, 2.0);
		assertEquals(66.5, sun.azimuthDegrees, 2.5);
	}

	@Test
	public void londonWinterNoonIsLowInTheSouth()
	{
		SolarPosition sun = SolarPosition.compute(at("2024-12-21T12:00:00Z"), 51.5, -0.13);
		assertEquals(15.0, sun.elevationDegrees, 1.0);
		assertEquals(180.0, sun.azimuthDegrees, 3.0);
	}

	@Test
	public void midnightIsBelowTheHorizon()
	{
		SolarPosition sun = SolarPosition.compute(at("2024-03-20T00:00:00Z"), 40.0, 0.0);
		assertEquals(true, sun.elevationDegrees < -40.0);
	}
}
