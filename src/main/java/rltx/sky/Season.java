package rltx.sky;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** The meteorological season at a moment for a hemisphere, and how far through it the date is. */
public final class Season
{
	public enum Kind
	{
		SPRING, SUMMER, AUTUMN, WINTER
	}

	public final Kind kind;
	/** 0 at the season's first day, approaching 1 at its last. */
	public final float progress;

	Season(Kind kind, float progress)
	{
		this.kind = kind;
		this.progress = progress;
	}

	/** Seasons start on the first of March, June, September and December in the north; the south runs half a year behind. */
	public static Season at(long epochMillis, double latitudeDegrees)
	{
		LocalDate date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate();
		int shifted = (date.getMonthValue() - 3 + (latitudeDegrees < 0 ? 6 : 0) + 12) % 12;
		float progress = (shifted % 3 + (date.getDayOfMonth() - 1) / (float) date.lengthOfMonth()) / 3f;
		return new Season(Kind.values()[shifted / 3], progress);
	}
}
