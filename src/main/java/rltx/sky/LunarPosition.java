package rltx.sky;

/**
 * The moon's place in the sky and how much of it the sun lights, from the leading terms of
 * Meeus's lunar theory: good to about half a degree, ample for a moon drawn in a game sky.
 */
public final class LunarPosition
{
	public final double raDegrees;
	public final double decDegrees;
	/** 0 at new moon, 1 at full. */
	public final double illuminatedFraction;

	private LunarPosition(double raDegrees, double decDegrees, double illuminatedFraction)
	{
		this.raDegrees = raDegrees;
		this.decDegrees = decDegrees;
		this.illuminatedFraction = illuminatedFraction;
	}

	public static LunarPosition compute(long epochMillis)
	{
		double days = epochMillis / 86_400_000.0 + 2440587.5 - 2451545.0;
		double t = days / 36525.0;
		double meanLongitude = wrap360(218.3164477 + 481267.88123421 * t);
		double elongation = Math.toRadians(wrap360(297.8501921 + 445267.1114034 * t));
		double sunAnomaly = Math.toRadians(wrap360(357.5291092 + 35999.0502909 * t));
		double moonAnomaly = Math.toRadians(wrap360(134.9633964 + 477198.8675055 * t));
		double latitudeArgument = Math.toRadians(wrap360(93.2720950 + 483202.0175233 * t));

		double longitude = meanLongitude
			+ 6.288774 * Math.sin(moonAnomaly)
			+ 1.274027 * Math.sin(2 * elongation - moonAnomaly)
			+ 0.658314 * Math.sin(2 * elongation)
			+ 0.213618 * Math.sin(2 * moonAnomaly)
			- 0.185116 * Math.sin(sunAnomaly)
			- 0.114332 * Math.sin(2 * latitudeArgument)
			+ 0.058793 * Math.sin(2 * elongation - 2 * moonAnomaly)
			+ 0.057066 * Math.sin(2 * elongation - sunAnomaly - moonAnomaly)
			+ 0.053322 * Math.sin(2 * elongation + moonAnomaly)
			+ 0.045758 * Math.sin(2 * elongation - sunAnomaly)
			- 0.040923 * Math.sin(sunAnomaly - moonAnomaly)
			- 0.034720 * Math.sin(elongation)
			- 0.030383 * Math.sin(sunAnomaly + moonAnomaly);
		double latitude = 5.128122 * Math.sin(latitudeArgument)
			+ 0.280602 * Math.sin(moonAnomaly + latitudeArgument)
			+ 0.277693 * Math.sin(moonAnomaly - latitudeArgument)
			+ 0.173237 * Math.sin(2 * elongation - latitudeArgument)
			+ 0.055413 * Math.sin(2 * elongation - moonAnomaly + latitudeArgument)
			+ 0.046271 * Math.sin(2 * elongation - moonAnomaly - latitudeArgument)
			+ 0.032573 * Math.sin(2 * elongation + latitudeArgument)
			+ 0.017198 * Math.sin(2 * moonAnomaly + latitudeArgument);

		double obliquity = Math.toRadians(23.439291 - 0.0130042 * t);
		double lon = Math.toRadians(longitude);
		double lat = Math.toRadians(latitude);
		double x = Math.cos(lat) * Math.cos(lon);
		double y = Math.cos(lat) * Math.sin(lon) * Math.cos(obliquity) - Math.sin(lat) * Math.sin(obliquity);
		double z = Math.cos(lat) * Math.sin(lon) * Math.sin(obliquity) + Math.sin(lat) * Math.cos(obliquity);
		double ra = wrap360(Math.toDegrees(Math.atan2(y, x)));
		double dec = Math.toDegrees(Math.asin(z));

		// The sun's ecliptic longitude as the solar almanac reckons it; the phase follows from the
		// angle between the two as seen from Earth.
		double sunMeanAnomaly = Math.toRadians(wrap360(357.528 + 0.9856003 * days));
		double sunLongitude = 280.460 + 0.9856474 * days + 1.915 * Math.sin(sunMeanAnomaly) + 0.020 * Math.sin(2 * sunMeanAnomaly);
		double separation = Math.acos(Math.max(-1.0, Math.min(1.0, Math.cos(lat) * Math.cos(Math.toRadians(longitude - sunLongitude)))));
		double fraction = (1.0 - Math.cos(separation)) / 2.0;
		return new LunarPosition(ra, dec, fraction);
	}

	private static double wrap360(double degrees)
	{
		double d = degrees % 360.0;
		return d < 0 ? d + 360.0 : d;
	}
}
