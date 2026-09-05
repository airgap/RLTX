package rltx.sky;

/**
 * Sky and precipitation conditions driving the renderer, either from a preset or from a
 * weather report. All amounts run from 0 to 1.
 */
public final class WeatherState
{
	public enum Preset
	{
		CLEAR, CLOUDY, OVERCAST, FOG, DRIZZLE, RAIN, STORM, SNOW, BLIZZARD
	}

	public float cloud;
	public float fog;
	public float rain;
	public float snow;
	/** Wind strength, 1 at roughly 15 m/s. */
	public float wind;
	/** Meteorological direction the wind blows from, degrees clockwise from north. */
	public float windFromDegrees = 270f;
	public boolean storm;

	public WeatherState()
	{
	}

	public WeatherState(float cloud, float fog, float rain, float snow, float wind, boolean storm)
	{
		this.cloud = cloud;
		this.fog = fog;
		this.rain = rain;
		this.snow = snow;
		this.wind = wind;
		this.storm = storm;
	}

	public static WeatherState preset(Preset preset)
	{
		switch (preset)
		{
			case CLOUDY:
				return new WeatherState(0.5f, 0f, 0f, 0f, 0.2f, false);
			case OVERCAST:
				return new WeatherState(0.95f, 0.05f, 0f, 0f, 0.3f, false);
			case FOG:
				return new WeatherState(0.8f, 0.8f, 0f, 0f, 0.05f, false);
			case DRIZZLE:
				return new WeatherState(0.9f, 0.15f, 0.3f, 0f, 0.25f, false);
			case RAIN:
				return new WeatherState(1f, 0.2f, 0.7f, 0f, 0.4f, false);
			case STORM:
				return new WeatherState(1f, 0.3f, 1f, 0f, 0.8f, true);
			case SNOW:
				return new WeatherState(0.9f, 0.25f, 0f, 0.6f, 0.2f, false);
			case BLIZZARD:
				return new WeatherState(1f, 0.5f, 0f, 1f, 0.9f, false);
			case CLEAR:
			default:
				return new WeatherState();
		}
	}

	/**
	 * A preset given the season's character: autumn is windier and greyer with mist late on,
	 * winter turns rain to snow under a heavier sky, spring is a touch cloudier. Real weather is
	 * left as reported. Seasons run 1 spring to 4 winter; 0 leaves the preset alone.
	 */
	public WeatherState seasonal(int season, float progress)
	{
		WeatherState s = new WeatherState(cloud, fog, rain, snow, wind, storm);
		s.windFromDegrees = windFromDegrees;
		switch (season)
		{
			case 1:
				s.cloud = Math.min(1f, cloud + 0.1f);
				break;
			case 3:
				s.wind = Math.min(1f, wind + 0.15f + 0.2f * progress);
				s.cloud = Math.min(1f, cloud + 0.15f);
				s.fog = Math.min(1f, fog + 0.12f * progress);
				break;
			case 4:
				s.snow = Math.max(snow, rain);
				s.rain = 0f;
				s.cloud = Math.min(1f, cloud + 0.1f);
				s.fog = Math.min(1f, fog + 0.05f);
				break;
			default:
				break;
		}
		return s;
	}

	/**
	 * Conditions from an Open-Meteo current-weather report: a WMO weather code sets the kind
	 * of weather, and the measured amounts refine its strength.
	 *
	 * @param rainMm     rain plus showers over the last hour, millimetres
	 * @param snowfallCm snowfall over the last hour, centimetres
	 * @param windMs     wind speed at 10 m, metres per second
	 */
	public static WeatherState fromReport(int wmoCode, float cloudCoverPercent, float rainMm, float snowfallCm, float windMs, float windFromDegrees)
	{
		WeatherState w = new WeatherState();
		w.cloud = Math.max(0f, Math.min(1f, cloudCoverPercent / 100f));
		w.wind = Math.max(0f, Math.min(1f, windMs / 15f));
		w.windFromDegrees = windFromDegrees;
		float codeRain = 0f;
		float codeSnow = 0f;
		switch (wmoCode)
		{
			case 45:
			case 48:
				w.fog = 0.7f;
				break;
			case 51:
			case 56:
				codeRain = 0.15f;
				break;
			case 53:
				codeRain = 0.25f;
				break;
			case 55:
			case 57:
				codeRain = 0.35f;
				break;
			case 61:
			case 66:
			case 80:
				codeRain = 0.4f;
				break;
			case 63:
			case 67:
			case 81:
				codeRain = 0.65f;
				break;
			case 65:
			case 82:
				codeRain = 0.9f;
				break;
			case 71:
			case 77:
				codeSnow = 0.35f;
				break;
			case 73:
			case 85:
				codeSnow = 0.6f;
				break;
			case 75:
			case 86:
				codeSnow = 0.9f;
				break;
			case 95:
				codeRain = 0.7f;
				w.storm = true;
				break;
			case 96:
			case 99:
				codeRain = 1f;
				w.storm = true;
				break;
			default:
				break;
		}
		w.rain = Math.max(codeRain, Math.min(1f, rainMm / 6f));
		w.snow = Math.max(codeSnow, Math.min(1f, snowfallCm / 2f));
		if (w.rain > 0f || w.snow > 0f)
		{
			w.cloud = Math.max(w.cloud, 0.85f);
			w.fog = Math.max(w.fog, 0.15f * Math.max(w.rain, w.snow));
		}
		if (w.storm)
		{
			w.cloud = 1f;
		}
		return w;
	}

	/** Moves this state a fraction of the way towards the target, so changes fade in. */
	public void approach(WeatherState target, float fraction)
	{
		cloud += (target.cloud - cloud) * fraction;
		fog += (target.fog - fog) * fraction;
		rain += (target.rain - rain) * fraction;
		snow += (target.snow - snow) * fraction;
		wind += (target.wind - wind) * fraction;
		windFromDegrees = target.windFromDegrees;
		storm = target.storm;
	}

	@Override
	public String toString()
	{
		return String.format("cloud=%.2f fog=%.2f rain=%.2f snow=%.2f wind=%.2f from %.0f° storm=%s", cloud, fog, rain, snow, wind, windFromDegrees, storm);
	}
}
