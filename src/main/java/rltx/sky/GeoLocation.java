package rltx.sky;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Where the machine is, for the real sun and weather: looked up once by IP address through a
 * keyless service, with the system time zone's longitude as the stand-in until it answers or
 * if it never does.
 */
@Slf4j
public final class GeoLocation
{
	private static final long RETRY_MILLIS = 30 * 60_000L;

	private final OkHttpClient http;
	private final Gson gson;
	private volatile double latitude;
	private volatile double longitude;
	private volatile boolean resolved;
	private volatile boolean inFlight;
	private volatile long nextAttempt;

	public GeoLocation(OkHttpClient http, Gson gson, double fallbackLatitude)
	{
		this.http = http;
		this.gson = gson;
		// A time zone spans about fifteen degrees of longitude per hour from Greenwich.
		int offsetSeconds = ZonedDateTime.now(ZoneId.systemDefault()).getOffset().getTotalSeconds();
		longitude = offsetSeconds / 3600.0 * 15.0;
		latitude = fallbackLatitude;
	}

	public double latitude()
	{
		return latitude;
	}

	public double longitude()
	{
		return longitude;
	}

	public boolean resolved()
	{
		return resolved;
	}

	/** Starts the lookup if it has not succeeded yet and enough time has passed since the last try. */
	public void poll()
	{
		long now = System.currentTimeMillis();
		if (resolved || inFlight || now < nextAttempt)
		{
			return;
		}
		inFlight = true;
		nextAttempt = now + RETRY_MILLIS;
		http.newCall(new Request.Builder().url("https://ipapi.co/json/").header("User-Agent", "RLTX RuneLite plugin").build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				inFlight = false;
				log.warn("Location lookup failed: {}", e.toString());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.warn("Location lookup returned HTTP {}", r.code());
						return;
					}
					JsonObject body = gson.fromJson(r.body().string(), JsonObject.class);
					latitude = body.get("latitude").getAsDouble();
					longitude = body.get("longitude").getAsDouble();
					resolved = true;
					log.info("Located at {}, {} ({})", latitude, longitude, body.has("city") ? body.get("city").getAsString() : "unknown city");
				}
				catch (IOException | JsonParseException | IllegalStateException | NullPointerException e)
				{
					log.warn("Location lookup unreadable: {}", e.toString());
				}
				finally
				{
					inFlight = false;
				}
			}
		});
	}
}
