package rltx;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.LinkBrowser;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The Lyku.org account link: the platform's OAuth sign-in for a client the player has registered
 * at developers.lyku.org, with an hour-long access token renewed from a ninety-day refresh token,
 * and its two-step profile picture upload, an upload slot from Lyku and the bytes sent to it, then
 * confirmed. An OAuth token's scopes there are route names, so only those two routes are asked for.
 */
@Slf4j
final class LykuOrg
{
	static final String API = "https://api.lyku.org";
	private static final String SCOPE = "authorize-pfp-upload confirm-pfp-upload";
	private static final String ACCESS_KEY = "lykuOrgAccess";
	private static final String REFRESH_KEY = "lykuOrgRefresh";
	private static final String EXPIRES_KEY = "lykuOrgExpires";
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");

	private final OkHttpClient http;
	private final Gson gson;
	private final ConfigManager configManager;
	private final RltxConfig config;
	private final Consumer<String> say;
	private Runnable connected;
	private boolean signingIn;

	LykuOrg(OkHttpClient http, Gson gson, ConfigManager configManager, RltxConfig config, Consumer<String> say)
	{
		this.http = http;
		this.gson = gson;
		this.configManager = configManager;
		this.config = config;
		this.say = say;
	}

	void onConnected(Runnable connected)
	{
		this.connected = connected;
	}

	/** Linked: a refresh token is held, whatever the state of the access token. */
	boolean connected()
	{
		return configManager.getConfiguration(RltxConfig.GROUP, REFRESH_KEY) != null;
	}

	synchronized void connect()
	{
		String clientId = config.lykuOrgClientId().trim();
		if (clientId.isEmpty())
		{
			say.accept("Lyku.org: enter the client id of an app registered at developers.lyku.org first");
			return;
		}
		if (signingIn)
		{
			say.accept("Lyku.org: a sign-in is already waiting in your browser");
			return;
		}
		signingIn = true;
		Thread worker = new Thread(() ->
		{
			try
			{
				signIn(clientId);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Lyku.org sign-in failed", e);
				say.accept("Lyku.org: sign-in failed, " + e.getMessage());
			}
			finally
			{
				synchronized (this)
				{
					signingIn = false;
				}
			}
		}, "rltx-lyku-org");
		worker.setDaemon(true);
		worker.start();
	}

	private void signIn(String clientId) throws IOException
	{
		OauthPkce pkce = new OauthPkce();
		try
		{
			String authorize = API + "/oauth-authorize?response_type=code&client_id=" + OauthPkce.encode(clientId)
				+ "&redirect_uri=" + OauthPkce.encode(pkce.redirect()) + "&scope=" + OauthPkce.encode(SCOPE)
				+ "&state=" + OauthPkce.encode(pkce.state) + "&code_challenge=" + OauthPkce.encode(pkce.challenge) + "&code_challenge_method=S256";
			LinkBrowser.browse(authorize);
			say.accept("Lyku.org: finish signing in in your browser");
			String code = pkce.awaitCode(10 * 60_000L);
			String form = "grant_type=authorization_code&code=" + OauthPkce.encode(code) + "&redirect_uri=" + OauthPkce.encode(pkce.redirect())
				+ "&client_id=" + OauthPkce.encode(clientId) + "&code_verifier=" + OauthPkce.encode(pkce.verifier);
			store(post(API + "/oauth-token", RequestBody.create(FORM, form), null));
		}
		finally
		{
			pkce.close();
		}
		say.accept("Lyku.org: connected");
		if (connected != null)
		{
			connected.run();
		}
	}

	private void store(JsonObject token)
	{
		configManager.setConfiguration(RltxConfig.GROUP, ACCESS_KEY, token.get("access_token").getAsString());
		if (token.has("refresh_token"))
		{
			configManager.setConfiguration(RltxConfig.GROUP, REFRESH_KEY, token.get("refresh_token").getAsString());
		}
		long expires = System.currentTimeMillis() + (token.has("expires_in") ? token.get("expires_in").getAsLong() : 3600L) * 1000L;
		configManager.setConfiguration(RltxConfig.GROUP, EXPIRES_KEY, Long.toString(expires));
	}

	void disconnect()
	{
		configManager.unsetConfiguration(RltxConfig.GROUP, ACCESS_KEY);
		configManager.unsetConfiguration(RltxConfig.GROUP, REFRESH_KEY);
		configManager.unsetConfiguration(RltxConfig.GROUP, EXPIRES_KEY);
	}

	// A live access token: the held one while it has a minute left, else a fresh one from the
	// refresh token, which Lyku rotates on every use.
	private String accessToken() throws IOException
	{
		String access = configManager.getConfiguration(RltxConfig.GROUP, ACCESS_KEY);
		String expires = configManager.getConfiguration(RltxConfig.GROUP, EXPIRES_KEY);
		if (access != null && expires != null && Long.parseLong(expires) > System.currentTimeMillis() + 60_000L)
		{
			return access;
		}
		String refresh = configManager.getConfiguration(RltxConfig.GROUP, REFRESH_KEY);
		if (refresh == null)
		{
			throw new IOException("not connected to Lyku.org");
		}
		String form = "grant_type=refresh_token&refresh_token=" + OauthPkce.encode(refresh) + "&client_id=" + OauthPkce.encode(config.lykuOrgClientId().trim());
		JsonObject token;
		try
		{
			token = post(API + "/oauth-token", RequestBody.create(FORM, form), null);
		}
		catch (IOException e)
		{
			disconnect();
			throw new IOException("the Lyku.org sign-in has expired; connect again (" + e.getMessage() + ")");
		}
		store(token);
		return token.get("access_token").getAsString();
	}

	/** Puts an encoded image up as the profile picture, off the calling thread. */
	void setProfilePictureAsync(byte[] bytes, String mime)
	{
		Thread worker = new Thread(() ->
		{
			try
			{
				String access = accessToken();
				JsonObject slot = post(API + "/authorize-pfp-upload", RequestBody.create(JSON, "{}"), access);
				String id = slot.get("id").getAsString();
				String uploadUrl = slot.get("url").getAsString();
				MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
					.addFormDataPart("file", "outfit." + (mime.endsWith("webp") ? "webp" : "png"), RequestBody.create(MediaType.get(mime), bytes))
					.build();
				try (Response response = http.newCall(new Request.Builder().url(uploadUrl).post(body).build()).execute())
				{
					if (!response.isSuccessful())
					{
						throw new IOException("the image host answered " + response.code());
					}
				}
				JsonObject confirm = new JsonObject();
				confirm.addProperty("id", id);
				post(API + "/confirm-pfp-upload", RequestBody.create(JSON, gson.toJson(confirm)), access);
				log.info("Lyku.org profile picture updated: {} bytes of {}", bytes.length, mime);
				say.accept("Lyku.org: profile picture updated to this outfit");
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Lyku.org profile picture not updated", e);
				say.accept("Lyku.org: profile picture not updated, " + e.getMessage());
			}
		}, "rltx-lyku-org");
		worker.setDaemon(true);
		worker.start();
	}

	// A JSON reply to a POST, as the account when a token is given.
	private JsonObject post(String url, RequestBody body, String token) throws IOException
	{
		Request.Builder request = new Request.Builder().url(url).post(body);
		if (token != null)
		{
			request.header("Authorization", "Bearer " + token);
		}
		try (Response response = http.newCall(request.build()).execute())
		{
			String text = response.body() == null ? "" : response.body().string();
			if (!response.isSuccessful())
			{
				throw new IOException("Lyku.org answered " + response.code() + (text.isEmpty() ? "" : ": " + text));
			}
			JsonObject reply = text.isEmpty() ? new JsonObject() : gson.fromJson(text, JsonObject.class);
			return reply == null ? new JsonObject() : reply;
		}
	}
}
