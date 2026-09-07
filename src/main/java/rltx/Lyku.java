package rltx;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.LinkBrowser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The Lyku account link: signing in through Lyku's OAuth server in the browser, with a client
 * registered on the spot and a PKCE code that comes back to a loopback port, and putting the
 * character's portrait up as the profile picture. The token is bound to one workspace and every
 * call names it.
 */
@Slf4j
final class Lyku
{
	static final String API = "https://api.lyku.org/work";
	private static final String TOKEN_KEY = "lykuToken";
	private static final String WORKSPACE_KEY = "lykuWorkspace";
	private static final String EXPIRES_KEY = "lykuTokenExpires";
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
	/** Lyku accepts up to two megabytes; a square of this size from the portrait is far under. */
	private static final int AVATAR_SIZE = 512;
	static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;

	private final OkHttpClient http;
	private final Gson gson;
	private final ConfigManager configManager;
	private final Consumer<String> say;
	private HttpServer callback;
	/** Run once a sign-in has completed. */
	private Runnable connected;

	Lyku(OkHttpClient http, Gson gson, ConfigManager configManager, Consumer<String> say)
	{
		this.http = http;
		this.gson = gson;
		this.configManager = configManager;
		this.say = say;
	}

	void onConnected(Runnable connected)
	{
		this.connected = connected;
	}

	boolean connected()
	{
		String token = configManager.getConfiguration(RltxConfig.GROUP, TOKEN_KEY);
		String expires = configManager.getConfiguration(RltxConfig.GROUP, EXPIRES_KEY);
		return token != null && (expires == null || Long.parseLong(expires) > System.currentTimeMillis());
	}

	/** Opens the browser on Lyku's consent page; the sign-in completes on a background thread. */
	synchronized void connect()
	{
		if (callback != null)
		{
			say.accept("Lyku: a sign-in is already waiting in your browser");
			return;
		}
		Thread worker = new Thread(() ->
		{
			try
			{
				signIn();
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Lyku sign-in failed", e);
				say.accept("Lyku: sign-in failed, " + e.getMessage());
				stopCallback();
			}
		}, "rltx-lyku");
		worker.setDaemon(true);
		worker.start();
	}

	private void signIn() throws IOException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		synchronized (this)
		{
			callback = server;
		}
		String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";

		JsonObject registration = new JsonObject();
		registration.add("redirect_uris", gson.toJsonTree(new String[]{redirect}));
		registration.addProperty("client_name", "RLTX");
		JsonObject client = post(API + "/oauth/register", RequestBody.create(JSON, gson.toJson(registration)), null, null);
		String clientId = client.get("client_id").getAsString();

		SecureRandom random = new SecureRandom();
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		random.nextBytes(bytes);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));

		String[] code = new String[1];
		server.createContext("/callback", exchange ->
		{
			Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
			boolean ok = state.equals(query.get("state")) && query.containsKey("code");
			String page = ok
				? "<html><body style='font-family:sans-serif;background:#141210;color:#f0ead6'><h2>RLTX is connected to Lyku.</h2><p>You can close this tab and go back to the game.</p></body></html>"
				: "<html><body style='font-family:sans-serif;background:#141210;color:#f0ead6'><h2>Sign-in did not complete.</h2><p>" + (query.containsKey("error") ? query.get("error") : "the reply did not match the request") + "</p></body></html>";
			byte[] body = page.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write(body);
			}
			synchronized (code)
			{
				code[0] = ok ? query.get("code") : "";
				code.notifyAll();
			}
		});
		server.start();

		String authorize = API + "/oauth/authorize?response_type=code&client_id=" + encode(clientId) + "&redirect_uri=" + encode(redirect)
			+ "&scope=write&state=" + encode(state) + "&code_challenge=" + encode(challenge) + "&code_challenge_method=S256";
		LinkBrowser.browse(authorize);
		say.accept("Lyku: finish signing in in your browser");

		synchronized (code)
		{
			long deadline = System.currentTimeMillis() + 10 * 60_000L;
			while (code[0] == null && System.currentTimeMillis() < deadline)
			{
				try
				{
					code.wait(deadline - System.currentTimeMillis());
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		stopCallback();
		if (code[0] == null)
		{
			throw new IOException("no reply from the browser within ten minutes");
		}
		if (code[0].isEmpty())
		{
			throw new IOException("Lyku did not grant access");
		}

		String form = "grant_type=authorization_code&code=" + encode(code[0]) + "&redirect_uri=" + encode(redirect)
			+ "&client_id=" + encode(clientId) + "&code_verifier=" + encode(verifier);
		JsonObject token = post(API + "/oauth/token", RequestBody.create(FORM, form), null, null);
		String access = token.get("access_token").getAsString();
		String workspace = token.has("workspace") ? token.get("workspace").getAsString() : null;
		long expires = System.currentTimeMillis() + token.get("expires_in").getAsLong() * 1000L;
		configManager.setConfiguration(RltxConfig.GROUP, TOKEN_KEY, access);
		configManager.setConfiguration(RltxConfig.GROUP, EXPIRES_KEY, Long.toString(expires));
		if (workspace != null)
		{
			configManager.setConfiguration(RltxConfig.GROUP, WORKSPACE_KEY, workspace);
		}
		say.accept("Lyku: connected" + (token.has("workspace_name") ? " to " + token.get("workspace_name").getAsString() : ""));
		if (connected != null)
		{
			connected.run();
		}
	}

	private synchronized void stopCallback()
	{
		if (callback != null)
		{
			callback.stop(0);
			callback = null;
		}
	}

	void disconnect()
	{
		configManager.unsetConfiguration(RltxConfig.GROUP, TOKEN_KEY);
		configManager.unsetConfiguration(RltxConfig.GROUP, EXPIRES_KEY);
		configManager.unsetConfiguration(RltxConfig.GROUP, WORKSPACE_KEY);
	}

	/**
	 * Puts the head and shoulders of a portrait up as the profile picture, off the calling thread.
	 * The portrait frames the figure with margins of fifteen percent of its height, so a square
	 * from just under the top edge holds the head down to the hips.
	 */
	void setAvatarAsync(int[] argb, int width, int height)
	{
		Thread worker = new Thread(() ->
		{
			try
			{
				BufferedImage portrait = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
				portrait.setRGB(0, 0, width, height, argb, 0, width);
				int side = Math.min(width, height);
				int top = Math.min(height - side, Math.round(0.05f * height));
				BufferedImage avatar = new BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = avatar.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.drawImage(portrait.getSubimage(0, top, side, side), 0, 0, AVATAR_SIZE, AVATAR_SIZE, null);
				g.dispose();
				ByteArrayOutputStream png = new ByteArrayOutputStream();
				ImageIO.write(avatar, "png", png);
				uploadAvatar(png.toByteArray(), "image/png");
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Lyku avatar not updated", e);
				say.accept("Lyku: profile picture not updated, " + e.getMessage());
			}
		}, "rltx-lyku");
		worker.setDaemon(true);
		worker.start();
	}

	/** Puts an image already encoded, such as a looping WebP of the idle cycle, up as the profile picture. */
	void setAvatarBytesAsync(byte[] bytes, String mime)
	{
		Thread worker = new Thread(() ->
		{
			try
			{
				uploadAvatar(bytes, mime);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Lyku avatar not updated", e);
				say.accept("Lyku: profile picture not updated, " + e.getMessage());
			}
		}, "rltx-lyku");
		worker.setDaemon(true);
		worker.start();
	}

	private void uploadAvatar(byte[] bytes, String mime) throws IOException
	{
		if (bytes.length > MAX_AVATAR_BYTES)
		{
			throw new IOException("the picture is over Lyku's two megabyte limit");
		}
		JsonObject body = new JsonObject();
		body.addProperty("dataUrl", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
		String token = configManager.getConfiguration(RltxConfig.GROUP, TOKEN_KEY);
		String workspace = configManager.getConfiguration(RltxConfig.GROUP, WORKSPACE_KEY);
		post(API + "/api/uploadAvatar", RequestBody.create(JSON, gson.toJson(body)), token, workspace);
		say.accept("Lyku: profile picture updated to this outfit");
	}

	// A JSON reply to a POST; a token and workspace, when given, name the account and the tenant.
	private JsonObject post(String url, RequestBody body, String token, String workspace) throws IOException
	{
		Request.Builder request = new Request.Builder().url(url).post(body);
		if (token != null)
		{
			request.header("Authorization", "Bearer " + token);
		}
		if (workspace != null)
		{
			request.header("x-tenant", workspace);
		}
		try (Response response = http.newCall(request.build()).execute())
		{
			String text = response.body() == null ? "" : response.body().string();
			if (response.code() == 401 && token != null)
			{
				disconnect();
				throw new IOException("the Lyku sign-in has expired; connect again");
			}
			if (!response.isSuccessful())
			{
				throw new IOException("Lyku answered " + response.code() + (text.isEmpty() ? "" : ": " + text));
			}
			return gson.fromJson(text, JsonObject.class);
		}
	}

	private static Map<String, String> query(String raw)
	{
		Map<String, String> out = new HashMap<>();
		if (raw == null)
		{
			return out;
		}
		for (String pair : raw.split("&"))
		{
			int eq = pair.indexOf('=');
			try
			{
				String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
				String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
				out.put(key, value);
			}
			catch (IOException e)
			{
				throw new IllegalArgumentException(e);
			}
		}
		return out;
	}

	private static String encode(String value)
	{
		try
		{
			return URLEncoder.encode(value, "UTF-8");
		}
		catch (IOException e)
		{
			throw new IllegalArgumentException(e);
		}
	}

	private static byte[] sha256(byte[] input)
	{
		try
		{
			return MessageDigest.getInstance("SHA-256").digest(input);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
