package rltx;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
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

/**
 * The pieces of an authorization-code sign-in with PKCE that a native client needs: the
 * verifier and its challenge, a state, and a loopback port that takes the browser's return and
 * hands the code back.
 */
final class OauthPkce
{
	final String verifier;
	final String challenge;
	final String state;
	private final HttpServer server;
	private final String[] code = new String[1];

	OauthPkce() throws IOException
	{
		SecureRandom random = new SecureRandom();
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		random.nextBytes(bytes);
		state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/callback", exchange ->
		{
			Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
			boolean ok = state.equals(query.get("state")) && query.containsKey("code");
			String page = ok
				? "<html><body style='font-family:sans-serif;background:#141210;color:#f0ead6'><h2>RLTX is connected.</h2><p>You can close this tab and go back to the game.</p></body></html>"
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
	}

	/** Where the browser is sent back to, on this machine alone. */
	String redirect()
	{
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
	}

	/** Waits for the browser's return; the code, or an exception when it was refused or never came. */
	String awaitCode(long timeoutMillis) throws IOException
	{
		synchronized (code)
		{
			long deadline = System.currentTimeMillis() + timeoutMillis;
			while (code[0] == null && System.currentTimeMillis() < deadline)
			{
				try
				{
					code.wait(Math.max(1, deadline - System.currentTimeMillis()));
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		if (code[0] == null)
		{
			throw new IOException("no reply from the browser within ten minutes");
		}
		if (code[0].isEmpty())
		{
			throw new IOException("access was not granted");
		}
		return code[0];
	}

	void close()
	{
		server.stop(0);
	}

	static Map<String, String> query(String raw)
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
			catch (UnsupportedEncodingException e)
			{
				throw new IllegalStateException(e);
			}
		}
		return out;
	}

	static String encode(String value)
	{
		try
		{
			return URLEncoder.encode(value, "UTF-8");
		}
		catch (UnsupportedEncodingException e)
		{
			throw new IllegalStateException(e);
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
