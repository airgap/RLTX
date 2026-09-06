package rltx;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.HotkeyListener;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import rltx.vk.RtRenderer;

/**
 * Photo mode: the interface layer is left out of the composite, and two corners of the view act
 * as invisible buttons, top-left to restore it and bottom-right to save a photo. Also the photo
 * keys, click to focus, and writing photos and their linear companions to disk.
 */
@Slf4j
final class PhotoMode
{
	private static final int PHOTO_BUTTON = 96;

	private final Client client;
	private final RltxConfig config;
	private final ConfigManager configManager;
	private final DrawManager drawManager;
	private final Consumer<String> say;

	volatile boolean chromeHidden;
	private boolean hintShown;
	private volatile boolean burstPending;
	private volatile boolean quadPending;
	// Click to focus: the depth under the pointer, read back from the last frame, becomes the
	// fixed focus distance.
	private volatile boolean focusProbePending;
	private volatile int focusProbeX, focusProbeY;

	private final HotkeyListener photoModeKey = new HotkeyListener(() -> config().photoModeKey())
	{
		@Override
		public void hotkeyPressed()
		{
			chromeHidden = !chromeHidden;
			if (chromeHidden && !hintShown)
			{
				// Read once the interface comes back, since the corners give no other hint.
				hintShown = true;
				say.accept("Photo mode: the interface was hidden. Click the top-left corner to bring it back, the bottom-right to take a photo, or press the photo mode key again.");
			}
		}
	};

	private final HotkeyListener quadPhotoKey = new HotkeyListener(() -> config().quadPhotoKey())
	{
		@Override
		public void hotkeyPressed()
		{
			quadPending = true;
		}
	};

	private final MouseAdapter photoButtons = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (!chromeHidden)
			{
				return e;
			}
			int w = client.getCanvasWidth();
			int h = client.getCanvasHeight();
			if (e.getX() < PHOTO_BUTTON && e.getY() < PHOTO_BUTTON)
			{
				chromeHidden = false;
				e.consume();
			}
			else if (e.getX() > w - PHOTO_BUTTON && e.getY() > h - PHOTO_BUTTON)
			{
				takePhoto();
				e.consume();
			}
			else if (config.clickToFocus() && e.getButton() == MouseEvent.BUTTON1 && e.isControlDown())
			{
				focusProbeX = e.getX() - client.getViewportXOffset();
				focusProbeY = e.getY() - client.getViewportYOffset();
				focusProbePending = true;
				e.consume();
			}
			return e;
		}
	};

	PhotoMode(Client client, RltxConfig config, ConfigManager configManager, DrawManager drawManager, Consumer<String> say)
	{
		this.client = client;
		this.config = config;
		this.configManager = configManager;
		this.drawManager = drawManager;
		this.say = say;
	}

	// The hotkeys take their keybinds through this so that their field initialisers, which run
	// before the constructor body, never read the final field directly.
	private RltxConfig config()
	{
		return config;
	}

	void register(KeyManager keys, MouseManager mice)
	{
		keys.registerKeyListener(quadPhotoKey);
		keys.registerKeyListener(photoModeKey);
		mice.registerMouseListener(photoButtons);
	}

	void unregister(KeyManager keys, MouseManager mice)
	{
		keys.unregisterKeyListener(photoModeKey);
		keys.unregisterKeyListener(quadPhotoKey);
		mice.unregisterMouseListener(photoButtons);
		chromeHidden = false;
	}

	boolean focusProbePending()
	{
		return focusProbePending;
	}

	void probeFocus(RtRenderer renderer)
	{
		focusProbePending = false;
		float depth = renderer.readbackDepth(focusProbeX, focusProbeY);
		if (depth <= 0f)
		{
			say.accept("Focus: nothing there but sky");
			return;
		}
		int tiles = Math.max(1, Math.min(60, Math.round(depth / Perspective.LOCAL_TILE_SIZE)));
		configManager.setConfiguration(RltxConfig.GROUP, "focusMode", RltxConfig.FocusMode.MANUAL);
		configManager.setConfiguration(RltxConfig.GROUP, "focusDistance", tiles);
		say.accept("Focus: " + tiles + " tiles");
	}

	/** Whether a burst photo is waiting to be taken, clearing the request. */
	boolean takeBurst()
	{
		if (!burstPending)
		{
			return false;
		}
		burstPending = false;
		return true;
	}

	/** Whether a quad-resolution photo is waiting to be taken, clearing the request. */
	boolean takeQuad()
	{
		if (!quadPending)
		{
			return false;
		}
		quadPending = false;
		return true;
	}

	private void takePhoto()
	{
		if (config.photoBurst() > 0)
		{
			burstPending = true;
		}
		else
		{
			drawManager.requestNextFrameListener(this::savePhotoAsync);
		}
	}

	void savePhotoAsync(Image image)
	{
		savePhotoAsync(image, null, 0, 0, 1f);
	}

	void savePhotoAsync(Image image, float[] linear, int width, int height, float exposure)
	{
		Thread saver = new Thread(() -> savePhoto(image, linear, width, height, exposure), "rltx-photo");
		saver.setDaemon(true);
		saver.start();
	}

	/** Saves pixels read straight back from the renderer, the image assembled off the client thread. */
	void saveArgbAsync(int[] argb, int width, int height, float[] linear, float exposure)
	{
		Thread saver = new Thread(() ->
		{
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, width, height, argb, 0, width);
			savePhoto(image, linear, width, height, exposure);
		}, "rltx-photo");
		saver.setDaemon(true);
		saver.start();
	}

	private void savePhoto(Image image, float[] linear, int width, int height, float exposure)
	{
		File dir = new File(RuneLite.SCREENSHOT_DIR, "RLTX");
		if (!dir.exists() && !dir.mkdirs())
		{
			log.warn("Could not create {}", dir);
			return;
		}
		String stem = "photo-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
		File file = new File(dir, stem + ".png");
		try
		{
			ImageIO.write(toBuffered(image), "png", file);
			if (linear != null)
			{
				// Scaled by the exposure so that 1.0 is display white before the tone curve.
				for (int i = 0; i < linear.length; ++i)
				{
					linear[i] *= exposure;
				}
				HdrWriter.write(new File(dir, stem + ".hdr"), width, height, linear);
			}
		}
		catch (IOException e)
		{
			log.warn("Photo not saved to {}", file, e);
			return;
		}
		log.info("Photo saved to {}", file);
		say.accept("Photo saved to " + file.getName());
	}

	// The finished frame as the interface sees it, read back from the framebuffer the client is
	// about to present; the viewport holds its size in device pixels.
	static Image screenshot()
	{
		int[] viewport = new int[4];
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
		int width = viewport[2];
		int height = viewport[3];
		ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
		try
		{
			GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			int[] row = new int[width];
			for (int y = 0; y < height; ++y)
			{
				int base = (height - 1 - y) * width * 4;
				for (int x = 0; x < width; ++x)
				{
					int o = base + x * 4;
					row[x] = 0xff000000 | (pixels.get(o) & 0xff) << 16 | (pixels.get(o + 1) & 0xff) << 8 | (pixels.get(o + 2) & 0xff);
				}
				image.setRGB(0, y, width, 1, row, 0, width);
			}
			return image;
		}
		finally
		{
			MemoryUtil.memFree(pixels);
		}
	}

	static BufferedImage toBuffered(Image image)
	{
		if (image instanceof BufferedImage)
		{
			return (BufferedImage) image;
		}
		BufferedImage buffered = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = buffered.createGraphics();
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return buffered;
	}
}
