package rltx.sky;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches the current weather at a location from Open-Meteo, which needs no API key, and
 * keeps the latest report. A failed fetch keeps the previous report and is retried on the
 * next interval.
 */
@Slf4j
public final class WeatherService
{
	private static final long REFRESH_MILLIS = 10 * 60_000L;
	private static final long RETRY_MILLIS = 60_000L;

	private final OkHttpClient http;
	private final Gson gson;
	private volatile WeatherState latest;
	private volatile long nextAttempt;
	private volatile boolean inFlight;
	private double lastLatitude = Double.NaN;
	private double lastLongitude = Double.NaN;

	public WeatherService(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	/** The most recent report, or null before the first one arrives. */
	public WeatherState latest()
	{
		return latest;
	}

	/** Starts a fetch when the refresh interval has elapsed or the location changed. */
	public void poll(double latitude, double longitude)
	{
		long now = System.currentTimeMillis();
		boolean moved = latitude != lastLatitude || longitude != lastLongitude;
		if (inFlight || (!moved && now < nextAttempt))
		{
			return;
		}
		lastLatitude = latitude;
		lastLongitude = longitude;
		inFlight = true;
		nextAttempt = now + RETRY_MILLIS;
		String url = String.format(Locale.ROOT,
			"https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
				+ "&current=weather_code,cloud_cover,rain,showers,snowfall,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms",
			latitude, longitude);
		http.newCall(new Request.Builder().url(url).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				inFlight = false;
				log.warn("Weather fetch failed: {}", e.toString());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.warn("Weather fetch returned HTTP {}", r.code());
						return;
					}
					JsonObject current = gson.fromJson(r.body().string(), JsonObject.class).getAsJsonObject("current");
					WeatherState state = WeatherState.fromReport(
						current.get("weather_code").getAsInt(),
						current.get("cloud_cover").getAsFloat(),
						current.get("rain").getAsFloat() + current.get("showers").getAsFloat(),
						current.get("snowfall").getAsFloat(),
						current.get("wind_speed_10m").getAsFloat(),
						current.get("wind_direction_10m").getAsFloat());
					latest = state;
					nextAttempt = System.currentTimeMillis() + REFRESH_MILLIS;
					log.info("Weather at {},{}: code {}, {}", lastLatitude, lastLongitude, current.get("weather_code").getAsInt(), state);
				}
				catch (IOException | JsonParseException | IllegalStateException | NullPointerException e)
				{
					log.warn("Weather report unreadable: {}", e.toString());
				}
				finally
				{
					inFlight = false;
				}
			}
		});
	}
}
