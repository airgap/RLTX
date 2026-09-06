package rltx;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.util.HotkeyListener;

/**
 * A camera detached from the client's, flown with the keyboard and turned by middle-drag, kept
 * within a sphere about the character.
 */
final class FreeCamera
{
	private static final Set<Integer> FLIGHT_KEYS = Set.of(KeyEvent.VK_W, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_Q, KeyEvent.VK_E, KeyEvent.VK_SHIFT);

	private final Client client;
	private final RltxConfig config;

	volatile boolean on;
	float x, y, z, pitch, yaw;
	private float clientX, clientY, clientZ, clientPitch, clientYaw;
	private long nanos;
	private final Set<Integer> heldKeys = ConcurrentHashMap.newKeySet();
	private int lookX, lookY;
	private boolean looking;

	private final HotkeyListener toggleKey = new HotkeyListener(() -> config().freeCameraKey())
	{
		@Override
		public void hotkeyPressed()
		{
			on = !on;
			if (on)
			{
				x = clientX;
				y = clientY;
				z = clientZ;
				pitch = clientPitch;
				yaw = clientYaw;
				nanos = 0;
			}
			heldKeys.clear();
		}
	};

	private final KeyListener flightKeys = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
			if (on && "wasdqeWASDQE".indexOf(e.getKeyChar()) >= 0)
			{
				e.consume();
			}
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (on && FLIGHT_KEYS.contains(e.getKeyCode()))
			{
				heldKeys.add(e.getKeyCode());
				e.consume();
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
			if (heldKeys.remove(e.getKeyCode()) && on)
			{
				e.consume();
			}
		}
	};

	private final MouseAdapter freeLook = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (on && e.getButton() == MouseEvent.BUTTON2)
			{
				looking = true;
				lookX = e.getX();
				lookY = e.getY();
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent e)
		{
			if (looking)
			{
				// Dragging pulls the world with the mouse, as the client's own camera drag does.
				yaw -= (e.getX() - lookX) * 0.004f;
				pitch = Math.max(-1.45f, Math.min(1.45f, pitch - (e.getY() - lookY) * 0.004f));
				lookX = e.getX();
				lookY = e.getY();
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			if (looking && e.getButton() == MouseEvent.BUTTON2)
			{
				looking = false;
				e.consume();
			}
			return e;
		}
	};

	FreeCamera(Client client, RltxConfig config)
	{
		this.client = client;
		this.config = config;
	}

	// The hotkeys take their keybinds through this so that their field initialisers, which run
	// before the constructor body, never read the final field directly.
	private RltxConfig config()
	{
		return config;
	}

	void register(KeyManager keys, MouseManager mice)
	{
		keys.registerKeyListener(toggleKey);
		keys.registerKeyListener(flightKeys);
		mice.registerMouseListener(freeLook);
	}

	void unregister(KeyManager keys, MouseManager mice)
	{
		keys.unregisterKeyListener(toggleKey);
		keys.unregisterKeyListener(flightKeys);
		mice.unregisterMouseListener(freeLook);
		on = false;
		heldKeys.clear();
	}

	/** The client's own camera this frame, which the free camera starts from when switched on. */
	void setClientCamera(float x, float y, float z, float pitch, float yaw)
	{
		clientX = x;
		clientY = y;
		clientZ = z;
		clientPitch = pitch;
		clientYaw = yaw;
	}

	// Advances the detached camera by the held keys since the last frame.
	void fly()
	{
		long now = System.nanoTime();
		float dt = nanos == 0 ? 0f : Math.min((now - nanos) / 1e9f, 0.1f);
		nanos = now;
		float speed = 6f * Perspective.LOCAL_TILE_SIZE * config.freeCameraSpeed() / 100f * (heldKeys.contains(KeyEvent.VK_SHIFT) ? 3f : 1f) * dt;
		float[] inv = new float[9];
		CameraMath.inverseRotation(pitch, yaw, inv);
		float forward = (heldKeys.contains(KeyEvent.VK_W) ? 1f : 0f) - (heldKeys.contains(KeyEvent.VK_S) ? 1f : 0f);
		float right = (heldKeys.contains(KeyEvent.VK_D) ? 1f : 0f) - (heldKeys.contains(KeyEvent.VK_A) ? 1f : 0f);
		float up = (heldKeys.contains(KeyEvent.VK_E) ? 1f : 0f) - (heldKeys.contains(KeyEvent.VK_Q) ? 1f : 0f);
		x += (inv[2] * forward + inv[0] * right) * speed;
		y += (inv[5] * forward + inv[3] * right) * speed - up * speed;
		z += (inv[8] * forward + inv[6] * right) * speed;
		tether();
	}

	// The free camera stays within a sphere about the character, so it frames shots around them
	// rather than roaming the loaded area.
	private void tether()
	{
		Player local = client.getLocalPlayer();
		LocalPoint lp = local == null ? null : local.getLocalLocation();
		if (lp == null)
		{
			return;
		}
		float centreX = lp.getX();
		float centreY = Perspective.getTileHeight(client, lp, local.getWorldLocation().getPlane()) - 120f;
		float centreZ = lp.getY();
		float range = config.freeCameraRange() * Perspective.LOCAL_TILE_SIZE;
		float dx = x - centreX, dy = y - centreY, dz = z - centreZ;
		float d2 = dx * dx + dy * dy + dz * dz;
		if (d2 > range * range)
		{
			float k = range / (float) Math.sqrt(d2);
			x = centreX + dx * k;
			y = centreY + dy * k;
			z = centreZ + dz * k;
		}
	}
}
