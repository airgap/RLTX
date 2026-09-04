package rltx.sky;

/**
 * Sun direction for a moment in time and a place on Earth, from the low-precision
 * almanac formulas (accurate to well under a degree, plenty for shadows).
 */
public final class SolarPosition
{
	public final double azimuthDegrees;
	public final double elevationDegrees;

	private SolarPosition(double azimuthDegrees, double elevationDegrees)
	{
		this.azimuthDegrees = azimuthDegrees;
		this.elevationDegrees = elevationDegrees;
	}

	/**
	 * @param epochMillis     UTC time
	 * @param latitudeDegrees north positive
	 * @param longitudeDegrees east positive
	 * @return azimuth clockwise from north, and elevation above the horizon, both in degrees
	 */
	public static SolarPosition compute(long epochMillis, double latitudeDegrees, double longitudeDegrees)
	{
		double daysSinceJ2000 = epochMillis / 86_400_000.0 + 2440587.5 - 2451545.0;

		double meanLongitude = wrap360(280.460 + 0.9856474 * daysSinceJ2000);
		double meanAnomaly = Math.toRadians(wrap360(357.528 + 0.9856003 * daysSinceJ2000));
		double eclipticLongitude = Math.toRadians(meanLongitude + 1.915 * Math.sin(meanAnomaly) + 0.020 * Math.sin(2 * meanAnomaly));
		double obliquity = Math.toRadians(23.439 - 0.0000004 * daysSinceJ2000);

		double rightAscension = Math.atan2(Math.cos(obliquity) * Math.sin(eclipticLongitude), Math.cos(eclipticLongitude));
		double declination = Math.asin(Math.sin(obliquity) * Math.sin(eclipticLongitude));

		double siderealHours = 18.697374558 + 24.06570982441908 * daysSinceJ2000;
		double localSiderealDegrees = wrap360(siderealHours * 15.0 + longitudeDegrees);
		double hourAngle = Math.toRadians(localSiderealDegrees) - rightAscension;

		double latitude = Math.toRadians(latitudeDegrees);
		double east = -Math.cos(declination) * Math.sin(hourAngle);
		double north = Math.sin(declination) * Math.cos(latitude) - Math.cos(declination) * Math.cos(hourAngle) * Math.sin(latitude);
		double up = Math.sin(declination) * Math.sin(latitude) + Math.cos(declination) * Math.cos(hourAngle) * Math.cos(latitude);

		double azimuth = wrap360(Math.toDegrees(Math.atan2(east, north)));
		double elevation = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, up))));
		return new SolarPosition(azimuth, elevation);
	}

	private static double wrap360(double degrees)
	{
		double r = degrees % 360.0;
		return r < 0 ? r + 360.0 : r;
	}
}
