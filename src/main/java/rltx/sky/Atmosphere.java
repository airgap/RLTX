package rltx.sky;

/**
 * The sky from sunlight scattered once in the air: Rayleigh scattering by the air itself and Mie
 * scattering by haze, integrated along the view ray through a spherical atmosphere with the
 * light attenuated on its way in. Rendered into a small equirectangular map of scene directions,
 * azimuth along x from north and elevation down y from the zenith, for the shaders to sample.
 */
public final class Atmosphere
{
	public static final int WIDTH = 96;
	public static final int HEIGHT = 48;

	private static final double EARTH_RADIUS = 6360e3;
	private static final double ATMOSPHERE_RADIUS = 6420e3;
	private static final double RAYLEIGH_HEIGHT = 7994;
	private static final double MIE_HEIGHT = 1200;
	private static final double[] BETA_RAYLEIGH = {5.8e-6, 13.5e-6, 33.1e-6};
	private static final double BETA_MIE = 21e-6;
	private static final double MIE_G = 0.76;
	private static final int VIEW_SAMPLES = 16;
	private static final int LIGHT_SAMPLES = 8;
	// Puts the noon zenith near the painted gradient's, so exposure and the rest of the scene need no retuning.
	private static final double SUN_SCALE = 20.0;

	private Atmosphere()
	{
	}

	/**
	 * Renders the map, RGBA floats row by row.
	 *
	 * @param lightX    unit vector toward the sun or moon in scene space, x east, y down, z north
	 * @param intensity brightness of that light, 1 for the full sun
	 * @param haze      0 for clear air rising to 1 for thick haze
	 */
	public static float[] render(double lightX, double lightY, double lightZ, double intensity, double haze)
	{
		float[] out = new float[WIDTH * HEIGHT * 4];
		double[] light = {lightX, -lightY, lightZ};
		double mie = BETA_MIE * (0.6 + 6.0 * haze);
		double[] radiance = new double[3];
		for (int j = 0; j < HEIGHT; ++j)
		{
			double elevation = (0.5 - (j + 0.5) / HEIGHT) * Math.PI;
			for (int i = 0; i < WIDTH; ++i)
			{
				double azimuth = ((i + 0.5) / WIDTH - 0.5) * 2.0 * Math.PI;
				double[] d = {Math.sin(azimuth) * Math.cos(elevation), Math.sin(elevation), Math.cos(azimuth) * Math.cos(elevation)};
				scatter(d, light, intensity * SUN_SCALE, mie, radiance);
				int o = (j * WIDTH + i) * 4;
				out[o] = (float) radiance[0];
				out[o + 1] = (float) radiance[1];
				out[o + 2] = (float) radiance[2];
				out[o + 3] = 1f;
			}
		}
		return out;
	}

	// Single scattering along a ray from just above the ground; y is up here.
	private static void scatter(double[] d, double[] light, double sun, double betaMie, double[] out)
	{
		double oy = EARTH_RADIUS + 100.0;
		double tMax = exitDistance(0.0, oy, 0.0, d, ATMOSPHERE_RADIUS);
		double ground = exitDistance(0.0, oy, 0.0, d, EARTH_RADIUS);
		if (ground > 0.0)
		{
			tMax = Math.min(tMax, ground);
		}
		double segment = tMax / VIEW_SAMPLES;
		double[] sumR = new double[3];
		double[] sumM = new double[3];
		double depthR = 0.0;
		double depthM = 0.0;
		for (int i = 0; i < VIEW_SAMPLES; ++i)
		{
			double t = segment * (i + 0.5);
			double px = d[0] * t;
			double py = oy + d[1] * t;
			double pz = d[2] * t;
			double height = Math.sqrt(px * px + py * py + pz * pz) - EARTH_RADIUS;
			double hr = Math.exp(-height / RAYLEIGH_HEIGHT) * segment;
			double hm = Math.exp(-height / MIE_HEIGHT) * segment;
			depthR += hr;
			depthM += hm;
			// The light's own path in: none if the planet stands in the way.
			if (exitDistance(px, py, pz, light, EARTH_RADIUS) > 0.0)
			{
				continue;
			}
			double lightMax = exitDistance(px, py, pz, light, ATMOSPHERE_RADIUS);
			double lightSegment = lightMax / LIGHT_SAMPLES;
			double lightR = 0.0;
			double lightM = 0.0;
			for (int k = 0; k < LIGHT_SAMPLES; ++k)
			{
				double s = lightSegment * (k + 0.5);
				double qx = px + light[0] * s;
				double qy = py + light[1] * s;
				double qz = pz + light[2] * s;
				double h = Math.sqrt(qx * qx + qy * qy + qz * qz) - EARTH_RADIUS;
				lightR += Math.exp(-h / RAYLEIGH_HEIGHT) * lightSegment;
				lightM += Math.exp(-h / MIE_HEIGHT) * lightSegment;
			}
			for (int c = 0; c < 3; ++c)
			{
				double tau = BETA_RAYLEIGH[c] * (depthR + lightR) + betaMie * 1.1 * (depthM + lightM);
				double attenuation = Math.exp(-tau);
				sumR[c] += attenuation * hr;
				sumM[c] += attenuation * hm;
			}
		}
		double mu = d[0] * light[0] + d[1] * light[1] + d[2] * light[2];
		double phaseR = 3.0 / (16.0 * Math.PI) * (1.0 + mu * mu);
		double g2 = MIE_G * MIE_G;
		double phaseM = 3.0 / (8.0 * Math.PI) * (1.0 - g2) * (1.0 + mu * mu) / ((2.0 + g2) * Math.pow(1.0 + g2 - 2.0 * MIE_G * mu, 1.5));
		for (int c = 0; c < 3; ++c)
		{
			out[c] = sun * (sumR[c] * BETA_RAYLEIGH[c] * phaseR + sumM[c] * betaMie * phaseM);
		}
	}

	// Distance along the ray to where it leaves a sphere about the origin, or the nearer entry
	// when it starts outside; negative when it never meets it.
	private static double exitDistance(double ox, double oy, double oz, double[] d, double radius)
	{
		double b = ox * d[0] + oy * d[1] + oz * d[2];
		double c = ox * ox + oy * oy + oz * oz - radius * radius;
		double disc = b * b - c;
		if (disc < 0.0)
		{
			return -1.0;
		}
		double root = Math.sqrt(disc);
		double near = -b - root;
		double far = -b + root;
		return near > 0.0 ? near : far;
	}
}
