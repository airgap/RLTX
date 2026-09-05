package rltx.sky;

/**
 * Where the fixed stars stand for an observer: the rotation from equatorial coordinates, x
 * toward the vernal equinox and z toward the north celestial pole, to scene directions, x east,
 * y down and z north, at a moment and place.
 */
public final class Sidereal
{
	private Sidereal()
	{
	}

	/** Local sidereal time in degrees, the same clock the solar almanac keeps. */
	public static double localDegrees(long epochMillis, double longitudeDegrees)
	{
		double daysSinceJ2000 = epochMillis / 86_400_000.0 + 2440587.5 - 2451545.0;
		double hours = 18.697374558 + 24.06570982441908 * daysSinceJ2000;
		double degrees = (hours * 15.0 + longitudeDegrees) % 360.0;
		return degrees < 0 ? degrees + 360.0 : degrees;
	}

	/**
	 * Columns of the equatorial to scene rotation: {@code out[3 * j + i]} is component i of the
	 * scene direction of equatorial axis j.
	 */
	public static void rotation(long epochMillis, double latitudeDegrees, double longitudeDegrees, float[] out)
	{
		double[][] frame = horizonFrame(epochMillis, latitudeDegrees, longitudeDegrees);
		for (int j = 0; j < 3; ++j)
		{
			out[3 * j] = (float) frame[0][j];
			out[3 * j + 1] = (float) -frame[1][j];
			out[3 * j + 2] = (float) frame[2][j];
		}
	}

	/** Scene direction of a right ascension and declination, in degrees. */
	public static float[] worldDirection(long epochMillis, double latitudeDegrees, double longitudeDegrees, double raDegrees, double decDegrees)
	{
		double ra = Math.toRadians(raDegrees);
		double dec = Math.toRadians(decDegrees);
		double[] e = {Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)};
		double[][] frame = horizonFrame(epochMillis, latitudeDegrees, longitudeDegrees);
		return new float[]{(float) dot(e, frame[0]), (float) -dot(e, frame[1]), (float) dot(e, frame[2])};
	}

	// The observer's east, up and north as equatorial vectors.
	private static double[][] horizonFrame(long epochMillis, double latitudeDegrees, double longitudeDegrees)
	{
		double theta = Math.toRadians(localDegrees(epochMillis, longitudeDegrees));
		double phi = Math.toRadians(latitudeDegrees);
		double[] east = {-Math.sin(theta), Math.cos(theta), 0.0};
		double[] up = {Math.cos(phi) * Math.cos(theta), Math.cos(phi) * Math.sin(theta), Math.sin(phi)};
		double[] north = {-Math.sin(phi) * Math.cos(theta), -Math.sin(phi) * Math.sin(theta), Math.cos(phi)};
		return new double[][]{east, up, north};
	}

	private static double dot(double[] a, double[] b)
	{
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}
}
