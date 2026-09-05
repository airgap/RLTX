package rltx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.Test;
import rltx.sky.Season;

public class SeasonTest
{
	private static long millis(int year, int month, int day)
	{
		return LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	@Test
	public void julyIsSummerInTheNorthAndWinterInTheSouth()
	{
		assertEquals(Season.Kind.SUMMER, Season.at(millis(2026, 7, 15), 51.5).kind);
		assertEquals(Season.Kind.WINTER, Season.at(millis(2026, 7, 15), -33.9).kind);
	}

	@Test
	public void autumnRunsFromSeptemberToNovember()
	{
		Season first = Season.at(millis(2026, 9, 1), 40.0);
		Season last = Season.at(millis(2026, 11, 30), 40.0);
		assertEquals(Season.Kind.AUTUMN, first.kind);
		assertEquals(Season.Kind.AUTUMN, last.kind);
		assertEquals(0f, first.progress, 1e-6f);
		assertTrue(last.progress > 0.95f && last.progress < 1f);
		assertEquals(Season.Kind.WINTER, Season.at(millis(2026, 12, 1), 40.0).kind);
	}
}
