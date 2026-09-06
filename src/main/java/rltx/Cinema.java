package rltx;

import com.google.gson.Gson;
import java.awt.Image;
import java.awt.event.KeyEvent;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.HotkeyListener;

/**
 * Cinema mode: camera poses recorded from the free camera, rendered as a smooth path through
 * them at photo quality, one image per output frame, for assembling into a video; or played
 * live as a preview.
 */
@Slf4j
final class Cinema
{
	private final RltxConfig config;
	private final FreeCamera camera;
	private final PhotoMode photo;
	private final DrawManager drawManager;
	private final Consumer<String> say;
	final CinemaPaths paths;

	private final List<double[]> keys = new ArrayList<>();
	private volatile int index = -1;
	// The clock and manual sun the path is at while rendering, when it follows the keyframes' own.
	private volatile long pathNow = -1;
	private double pathAzimuth, pathElevation;
	private volatile boolean stop;
	private int total;
	private File dir;
	private boolean chromeWasHidden;
	private ExecutorService writer;
	// Playing the path live rather than rendering it, and the encoder the frames are piped to.
	private volatile boolean preview;
	private Process encoder;
	private OutputStream pipe;

	// The cinema keys mean nothing outside the free camera, and must not swallow letters typed
	// into the chat when it is not flying.
	private abstract class FlightHotkey extends HotkeyListener
	{
		FlightHotkey(Supplier<Keybind> keybind)
		{
			super(keybind);
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (camera.on)
			{
				super.keyPressed(e);
			}
		}
	}

	private final HotkeyListener keyframeKey = new FlightHotkey(() -> config().cinemaKeyframeKey())
	{
		@Override
		public void hotkeyPressed()
		{
			if (index >= 0)
			{
				return;
			}
			if (!camera.on)
			{
				say.accept("Cinema: keyframes are recorded from the free camera");
				return;
			}
			keys.add(new double[]{camera.x, camera.y, camera.z, camera.pitch, camera.yaw, clock(), config.sunAzimuth(), config.sunElevation()});
			say.accept("Cinema: keyframe " + keys.size() + " recorded");
		}
	};

	private final HotkeyListener clearKey = new FlightHotkey(() -> config().cinemaClearKey())
	{
		@Override
		public void hotkeyPressed()
		{
			if (index < 0)
			{
				keys.clear();
				say.accept("Cinema: keyframes cleared");
			}
		}
	};

	private final HotkeyListener renderKey = new FlightHotkey(() -> config().cinemaRenderKey())
	{
		@Override
		public void hotkeyPressed()
		{
			if (index >= 0)
			{
				stop = true;
			}
			else if (keys.size() < 2)
			{
				say.accept("Cinema: record at least two keyframes first");
			}
			else
			{
				start(false);
			}
		}
	};

	private final HotkeyListener previewKey = new FlightHotkey(() -> config().cinemaPreviewKey())
	{
		@Override
		public void hotkeyPressed()
		{
			if (index >= 0)
			{
				stop = true;
			}
			else if (keys.size() < 2)
			{
				say.accept("Cinema: record at least two keyframes first");
			}
			else
			{
				start(true);
			}
		}
	};

	// The panel's handle on the cinema: the same actions as the keys, plus the saved paths.
	final ControlPanel.Cinema control = new ControlPanel.Cinema()
	{
		@Override
		public int keyframes()
		{
			return keys.size();
		}

		@Override
		public String state()
		{
			if (index >= 0)
			{
				return (preview ? "Previewing frame " : "Rendering frame ") + index + " of " + total;
			}
			return camera.on ? "Free camera on; " + keys.size() + " keyframes" : "Turn the free camera on to record keyframes";
		}

		@Override
		public void record()
		{
			keyframeKey.hotkeyPressed();
		}

		@Override
		public void clear()
		{
			clearKey.hotkeyPressed();
		}

		@Override
		public void render()
		{
			if (index < 0 && keys.size() >= 2)
			{
				start(false);
			}
		}

		@Override
		public void preview()
		{
			if (index < 0 && keys.size() >= 2)
			{
				start(true);
			}
		}

		@Override
		public void stop()
		{
			stop = true;
		}

		@Override
		public List<double[]> export()
		{
			return new ArrayList<>(keys);
		}

		@Override
		public void load(List<double[]> loaded)
		{
			if (index < 0)
			{
				keys.clear();
				keys.addAll(loaded);
			}
		}
	};

	Cinema(RltxConfig config, FreeCamera camera, PhotoMode photo, DrawManager drawManager, Gson gson, Consumer<String> say)
	{
		this.config = config;
		this.camera = camera;
		this.photo = photo;
		this.drawManager = drawManager;
		this.say = say;
		this.paths = new CinemaPaths(gson);
	}

	// The hotkeys take their keybinds through this so that their field initialisers, which run
	// before the constructor body, never read the final field directly.
	private RltxConfig config()
	{
		return config;
	}

	void register(KeyManager keyManager)
	{
		keyManager.registerKeyListener(keyframeKey);
		keyManager.registerKeyListener(clearKey);
		keyManager.registerKeyListener(renderKey);
		keyManager.registerKeyListener(previewKey);
	}

	void unregister(KeyManager keyManager)
	{
		keyManager.unregisterKeyListener(keyframeKey);
		keyManager.unregisterKeyListener(clearKey);
		keyManager.unregisterKeyListener(renderKey);
		keyManager.unregisterKeyListener(previewKey);
	}

	/** Whether a path is being rendered or previewed. */
	boolean active()
	{
		return index >= 0;
	}

	boolean preview()
	{
		return preview;
	}

	/** Whether the sun should follow the path's own clock and manual settings this frame. */
	boolean pathTime()
	{
		return index >= 0 && pathNow >= 0;
	}

	long pathNow()
	{
		return pathNow;
	}

	double pathAzimuth()
	{
		return pathAzimuth;
	}

	double pathElevation()
	{
		return pathElevation;
	}

	/** The path's own time in seconds at the current frame, so water, rain and flames run at the sequence's rate. */
	float seconds()
	{
		return index / (float) config.cinemaFps();
	}

	// The moment the sun is computed for: the real clock with the chosen offset.
	long clock()
	{
		return System.currentTimeMillis() + (config.sunMode() == RltxConfig.SunMode.REAL_TIME_SET ? config.timeOffset() * 3_600_000L : 0L);
	}

	private void start(boolean preview)
	{
		total = (keys.size() - 1) * config.cinemaSeconds() * config.cinemaFps();
		this.preview = preview;
		chromeWasHidden = photo.chromeHidden;
		if (!preview)
		{
			File target = new File(RuneLite.SCREENSHOT_DIR, "RLTX/cinema-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
			if (!target.mkdirs())
			{
				say.accept("Cinema: could not create " + target);
				return;
			}
			dir = target;
			writer = Executors.newSingleThreadExecutor(r ->
			{
				Thread t = new Thread(r, "rltx-cinema");
				t.setDaemon(true);
				return t;
			});
			pipe = config.cinemaEncode() ? startEncoder(target) : null;
			photo.chromeHidden = true;
		}
		camera.on = true;
		stop = false;
		index = 0;
		say.accept(preview ? "Cinema: previewing " + total + " frames" : "Cinema: rendering " + total + " frames to " + dir.getName()
			+ (pipe != null ? " through ffmpeg" : ""));
	}

	// ffmpeg, when it is on the path, takes the PNG frames on its standard input and writes the
	// video; its own output goes to a log beside it so it can never block on a full pipe.
	private OutputStream startEncoder(File dir)
	{
		try
		{
			Process probe = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			if (!probe.waitFor(5, TimeUnit.SECONDS) || probe.exitValue() != 0)
			{
				return null;
			}
			encoder = new ProcessBuilder("ffmpeg", "-y", "-f", "image2pipe", "-framerate", Integer.toString(config.cinemaFps()), "-i", "-",
				"-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", new File(dir, "cinema.mp4").getPath())
				.redirectErrorStream(true).redirectOutput(new File(dir, "ffmpeg.log")).start();
			return new BufferedOutputStream(encoder.getOutputStream(), 1 << 20);
		}
		catch (IOException e)
		{
			log.warn("ffmpeg not usable; writing PNG frames instead", e);
			return null;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private void finish()
	{
		int rendered = index;
		index = -1;
		pathNow = -1;
		photo.chromeHidden = chromeWasHidden;
		if (preview)
		{
			say.accept("Cinema: preview finished");
			return;
		}
		OutputStream closing = pipe;
		Process finishing = encoder;
		pipe = null;
		encoder = null;
		File written = dir;
		int fps = config.cinemaFps();
		if (closing != null)
		{
			writer.execute(() ->
			{
				try
				{
					closing.close();
					finishing.waitFor();
					say.accept("Cinema: " + rendered + " frames encoded to " + new File(written, "cinema.mp4"));
				}
				catch (IOException e)
				{
					log.warn("Closing the ffmpeg pipe failed", e);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
			});
		}
		else
		{
			say.accept("Cinema: " + rendered + " frames in " + written + ". Assemble with: ffmpeg -framerate " + fps
				+ " -i frame-%05d.png -c:v libx264 -pix_fmt yuv420p cinema.mp4");
		}
		writer.shutdown();
	}

	/**
	 * Puts the free camera at the current frame of the path: a Catmull-Rom spline through the
	 * keyframes, one segment per keyframe interval with the ends clamped, and yaw unwrapped so the
	 * camera turns the short way.
	 */
	void pose()
	{
		int perSegment = config.cinemaSeconds() * config.cinemaFps();
		float s = Math.min(index / (float) perSegment, keys.size() - 1 - 1e-4f);
		int k = (int) s;
		float t = s - k;
		if (config.cinemaEasing() == RltxConfig.CinemaEasing.EASE)
		{
			// Slowing into and out of each keyframe, the way a dolly is driven.
			t = t * t * (3f - 2f * t);
		}
		double[] a = keys.get(Math.max(k - 1, 0));
		double[] b = keys.get(k);
		double[] c = keys.get(k + 1);
		double[] d = keys.get(Math.min(k + 2, keys.size() - 1));
		camera.x = (float) catmullRom(a[0], b[0], c[0], d[0], t);
		camera.y = (float) catmullRom(a[1], b[1], c[1], d[1], t);
		camera.z = (float) catmullRom(a[2], b[2], c[2], d[2], t);
		camera.pitch = (float) catmullRom(a[3], b[3], c[3], d[3], t);
		double yawA = unwrap(a[4], b[4], 2 * Math.PI);
		double yawC = unwrap(c[4], b[4], 2 * Math.PI);
		double yawD = unwrap(d[4], yawC, 2 * Math.PI);
		camera.yaw = (float) catmullRom(yawA, b[4], yawC, yawD, t);
		if (config.cinemaClock())
		{
			// Time runs evenly between keyframes, so the sun and stars travel with the camera.
			pathNow = Math.round(b[5] + (c[5] - b[5]) * t);
			pathAzimuth = b[6] + (unwrap(c[6], b[6], 360.0) - b[6]) * t;
			pathElevation = b[7] + (c[7] - b[7]) * t;
		}
	}

	private static double catmullRom(double a, double b, double c, double d, double t)
	{
		return 0.5 * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (3 * b - a - 3 * c + d) * t * t * t);
	}

	private static double unwrap(double angle, double near, double turn)
	{
		while (angle - near > turn / 2)
		{
			angle -= turn;
		}
		while (angle - near < -turn / 2)
		{
			angle += turn;
		}
		return angle;
	}

	/** After the current frame's burst: the presented image is written, and the path moves on. */
	void frameRendered()
	{
		int frame = index;
		drawManager.requestNextFrameListener(image -> writeFrame(frame, image));
		advance();
	}

	/** After a preview frame was shown live, with nothing saved. */
	void framePreviewed()
	{
		advance();
	}

	private void advance()
	{
		if (++index >= total || stop)
		{
			finish();
		}
	}

	private void writeFrame(int frame, Image image)
	{
		OutputStream target = pipe;
		File file = new File(dir, String.format("frame-%05d.png", frame));
		writer.execute(() ->
		{
			try
			{
				if (target != null)
				{
					ImageIO.write(PhotoMode.toBuffered(image), "png", target);
				}
				else
				{
					ImageIO.write(PhotoMode.toBuffered(image), "png", file);
				}
			}
			catch (IOException e)
			{
				log.warn("Cinema frame not written ({})", file.getName(), e);
			}
		});
	}

	/** Abandons a render in progress when the plugin stops. */
	void shutDown()
	{
		if (index < 0)
		{
			return;
		}
		index = -1;
		pathNow = -1;
		if (writer != null)
		{
			writer.shutdown();
		}
		if (encoder != null)
		{
			encoder.destroy();
			encoder = null;
			pipe = null;
		}
	}
}
