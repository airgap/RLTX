package rltx;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.BeforeMenuRender;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * The right-click menu drawn in the renderer's style: the client is told not to draw its own,
 * and the same entries are laid out in the same place, at the client's row pitch so its own
 * hit testing still lands on what is shown, on a dark translucent panel with a lit border.
 */
final class StyledMenu extends Overlay
{
	/** The client's menu geometry: the header's height, each row's, and the padding under the last. */
	private static final int HEADER = 19;
	private static final int ROW = 15;
	private static final Pattern TAG = Pattern.compile("<([^>]*)>");
	private static final Color TEXT = new Color(0xf0, 0xea, 0xd6);
	private static final Color ACCENT = new Color(0xd6, 0xb5, 0x6c);
	private static final Color BORDER = new Color(0xd6, 0xb5, 0x6c, 0xa0);
	private static final Color HOVER = new Color(0xff, 0xff, 0xff, 0x2c);
	private static final Color SHADOW = new Color(0, 0, 0, 0xb0);

	private final Client client;
	private final RltxConfig config;

	StyledMenu(Client client, RltxConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(OverlayPriority.HIGHEST);
	}

	@Subscribe
	public void onBeforeMenuRender(BeforeMenuRender event)
	{
		if (config.styledMenus())
		{
			event.consume();
		}
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.styledMenus() || !client.isMenuOpen())
		{
			return null;
		}
		int x = client.getMenuX();
		int y = client.getMenuY();
		int width = client.getMenuWidth();
		int height = client.getMenuHeight();
		MenuEntry[] entries = client.getMenuEntries();
		int rows = (height - HEADER - 3) / ROW;
		int scroll = client.isMenuScrollable() ? client.getMenuScroll() : 0;

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		int alpha = Math.round(255f * config.menuOpacity() / 100f);
		g.setColor(new Color(0x14, 0x12, 0x10, alpha));
		g.fillRoundRect(x, y, width, height, 6, 6);
		g.setColor(BORDER);
		g.drawRoundRect(x, y, width - 1, height - 1, 6, 6);
		g.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 0x60));
		g.drawLine(x + 4, y + HEADER - 1, x + width - 5, y + HEADER - 1);

		Font font = FontManager.getRunescapeBoldFont();
		g.setFont(font);
		FontMetrics metrics = g.getFontMetrics();
		int baseline = (HEADER + metrics.getAscent() - metrics.getDescent()) / 2;
		shadowed(g, "Choose Option", x + 4, y + baseline, ACCENT);

		Point mouse = client.getMouseCanvasPosition();
		int hovered = -1;
		if (mouse != null && mouse.getX() >= x && mouse.getX() < x + width)
		{
			int row = (mouse.getY() - y - HEADER) / ROW;
			hovered = mouse.getY() >= y + HEADER && row < rows ? row : -1;
		}
		// The client keeps its entries bottom up: the last is the top row.
		for (int row = 0; row < rows; ++row)
		{
			int index = entries.length - 1 - row - scroll;
			if (index < 0)
			{
				break;
			}
			int top = y + HEADER + row * ROW;
			if (row == hovered)
			{
				g.setColor(HOVER);
				g.fillRoundRect(x + 2, top, width - 4, ROW, 4, 4);
			}
			MenuEntry entry = entries[index];
			String target = entry.getTarget();
			String text = target == null || target.isEmpty() ? entry.getOption() : entry.getOption() + " " + target;
			tagged(g, text, x + 4, top + (ROW + metrics.getAscent() - metrics.getDescent()) / 2);
		}
		if (client.isMenuScrollable())
		{
			int total = entries.length;
			int trackTop = y + HEADER;
			int trackHeight = rows * ROW;
			int thumb = Math.max(8, trackHeight * rows / Math.max(total, 1));
			int thumbTop = trackTop + (trackHeight - thumb) * scroll / Math.max(total - rows, 1);
			g.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 0x90));
			g.fillRoundRect(x + width - 5, thumbTop, 3, thumb, 2, 2);
		}
		return new Dimension(width, height);
	}

	// Text with the client's colour tags: <col=rrggbb> opens a colour, </col> closes it, and any
	// other tag, such as an icon, is dropped.
	private static void tagged(Graphics2D g, String text, int x, int baseline)
	{
		Color color = TEXT;
		Matcher m = TAG.matcher(text);
		int at = 0;
		int pen = x;
		while (m.find())
		{
			pen = shadowed(g, text.substring(at, m.start()), pen, baseline, color);
			String tag = m.group(1);
			if (tag.startsWith("col="))
			{
				try
				{
					color = new Color(Integer.parseInt(tag.substring(4), 16));
				}
				catch (NumberFormatException e)
				{
					color = TEXT;
				}
			}
			else if (tag.equals("/col"))
			{
				color = TEXT;
			}
			at = m.end();
		}
		shadowed(g, text.substring(at), pen, baseline, color);
	}

	// Draws a run of text with a drop shadow and returns where the next run starts.
	private static int shadowed(Graphics2D g, String text, int x, int baseline, Color color)
	{
		if (text.isEmpty())
		{
			return x;
		}
		g.setColor(SHADOW);
		g.drawString(text, x + 1, baseline + 1);
		g.setColor(color);
		g.drawString(text, x, baseline);
		return x + g.getFontMetrics().stringWidth(text);
	}
}
