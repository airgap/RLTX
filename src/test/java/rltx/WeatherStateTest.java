package rltx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import rltx.sky.WeatherState;

public class WeatherStateTest
{
	@Test
	public void clearSkyReportHasNoWeather()
	{
		WeatherState w = WeatherState.fromReport(0, 5f, 0f, 0f, 2f, 90f);
		assertEquals(0.05f, w.cloud, 1e-6);
		assertEquals(0f, w.rain, 0f);
		assertEquals(0f, w.snow, 0f);
		assertEquals(0f, w.fog, 0f);
		assertTrue(!w.storm);
	}

	@Test
	public void rainCodeImpliesCloudAndHaze()
	{
		WeatherState w = WeatherState.fromReport(61, 40f, 0f, 0f, 5f, 180f);
		assertEquals(0.4f, w.rain, 1e-6);
		assertTrue(w.cloud >= 0.85f);
		assertTrue(w.fog > 0f);
	}

	@Test
	public void heavyMeasuredRainOverridesLightCode()
	{
		WeatherState w = WeatherState.fromReport(51, 100f, 6f, 0f, 5f, 180f);
		assertEquals(1f, w.rain, 1e-6);
	}

	@Test
	public void fogAndThunderstormCodes()
	{
		assertEquals(0.7f, WeatherState.fromReport(45, 90f, 0f, 0f, 1f, 0f).fog, 1e-6);
		WeatherState storm = WeatherState.fromReport(95, 70f, 0f, 0f, 12f, 0f);
		assertTrue(storm.storm);
		assertEquals(1f, storm.cloud, 0f);
		assertEquals(0.8f, storm.wind, 1e-6);
	}

	@Test
	public void approachMovesPartWayAndCopiesDiscreteFields()
	{
		WeatherState now = new WeatherState();
		WeatherState target = WeatherState.preset(WeatherState.Preset.STORM);
		now.approach(target, 0.5f);
		assertEquals(0.5f, now.cloud, 1e-6);
		assertEquals(0.5f, now.rain, 1e-6);
		assertTrue(now.storm);
	}
}
