package rltx;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

/**
 * A floating window holding every RLTX setting, built from the config interface's annotations so
 * it stays in step with the sidebar panel: a tab per section, a control per item, both ways.
 */
final class ControlPanel
{
	private final ConfigManager configManager;
	private final RltxConfig config;
	private final Map<String, Consumer<Object>> refreshers = new HashMap<>();
	private JFrame frame;
	private boolean updating;

	ControlPanel(ConfigManager configManager, RltxConfig config)
	{
		this.configManager = configManager;
		this.config = config;
	}

	/** Shows the window, building it on first use; called on any thread. */
	void toggle()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (frame == null)
			{
				frame = build();
			}
			if (frame.isVisible())
			{
				frame.setVisible(false);
			}
			else
			{
				frame.setVisible(true);
				frame.toFront();
			}
		});
	}

	void dispose()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (frame != null)
			{
				frame.dispose();
				frame = null;
			}
		});
	}

	/** A setting changed elsewhere; the control showing it follows. */
	void refresh(String key)
	{
		SwingUtilities.invokeLater(() ->
		{
			Consumer<Object> refresher = refreshers.get(key);
			if (refresher != null)
			{
				updating = true;
				try
				{
					refresher.accept(current(key));
				}
				finally
				{
					updating = false;
				}
			}
		});
	}

	private final Map<String, Method> methods = new HashMap<>();

	private Object current(String key)
	{
		try
		{
			return methods.get(key).invoke(config);
		}
		catch (IllegalAccessException | InvocationTargetException e)
		{
			throw new IllegalStateException("Cannot read setting " + key, e);
		}
	}

	private void set(String key, Object value)
	{
		if (!updating)
		{
			configManager.setConfiguration(RltxConfig.GROUP, key, value);
		}
	}

	private JFrame build()
	{
		Map<String, String> sectionNames = new HashMap<>();
		Map<String, Integer> sectionOrder = new HashMap<>();
		for (Field field : RltxConfig.class.getDeclaredFields())
		{
			ConfigSection section = field.getAnnotation(ConfigSection.class);
			if (section != null)
			{
				try
				{
					String key = (String) field.get(null);
					sectionNames.put(key, section.name());
					sectionOrder.put(key, section.position());
				}
				catch (IllegalAccessException e)
				{
					throw new IllegalStateException(e);
				}
			}
		}
		Map<String, List<Method>> bySection = new HashMap<>();
		for (Method method : RltxConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item == null || item.hidden())
			{
				continue;
			}
			methods.put(item.keyName(), method);
			bySection.computeIfAbsent(item.section(), k -> new ArrayList<>()).add(method);
		}
		List<String> sections = new ArrayList<>(bySection.keySet());
		sections.sort(Comparator.comparingInt(s -> sectionOrder.getOrDefault(s, 99)));

		JTabbedPane tabs = new JTabbedPane();
		for (String section : sections)
		{
			List<Method> items = bySection.get(section);
			items.sort(Comparator.comparingInt(m -> m.getAnnotation(ConfigItem.class).position()));
			JPanel page = new JPanel(new GridBagLayout());
			page.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
			GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0;
			c.gridy = 0;
			c.weightx = 1;
			c.fill = GridBagConstraints.HORIZONTAL;
			c.insets = new Insets(3, 0, 3, 0);
			for (Method method : items)
			{
				JComponent row = row(method);
				if (row != null)
				{
					page.add(row, c);
					++c.gridy;
				}
			}
			c.weighty = 1;
			page.add(Box.createVerticalGlue(), c);
			JScrollPane scroll = new JScrollPane(page);
			scroll.setBorder(null);
			scroll.getVerticalScrollBar().setUnitIncrement(16);
			tabs.addTab(sectionNames.getOrDefault(section, section), scroll);
		}

		JFrame window = new JFrame("RLTX");
		window.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		window.getContentPane().setLayout(new BorderLayout());
		window.getContentPane().add(tabs, BorderLayout.CENTER);
		window.setSize(new Dimension(460, 720));
		window.setLocationByPlatform(true);
		return window;
	}

	private JComponent row(Method method)
	{
		ConfigItem item = method.getAnnotation(ConfigItem.class);
		String key = item.keyName();
		Class<?> type = method.getReturnType();
		Object value = current(key);
		JPanel row = new JPanel(new BorderLayout(8, 0));
		JLabel label = new JLabel(item.name());
		label.setToolTipText(item.description());
		row.add(label, BorderLayout.CENTER);
		JComponent control;
		if (type == boolean.class)
		{
			JCheckBox box = new JCheckBox();
			box.setSelected((Boolean) value);
			box.addActionListener(e -> set(key, box.isSelected()));
			refreshers.put(key, v -> box.setSelected((Boolean) v));
			control = box;
		}
		else if (type == int.class)
		{
			Range range = method.getAnnotation(Range.class);
			Units units = method.getAnnotation(Units.class);
			String suffix = units == null ? "" : units.value();
			if (range != null && range.max() - range.min() <= 4000)
			{
				JSlider slider = new JSlider(range.min(), range.max(), clamp((Integer) value, range.min(), range.max()));
				JLabel shown = new JLabel(slider.getValue() + suffix);
				shown.setPreferredSize(new Dimension(56, shown.getPreferredSize().height));
				slider.addChangeListener(e ->
				{
					shown.setText(slider.getValue() + suffix);
					if (!slider.getValueIsAdjusting())
					{
						set(key, slider.getValue());
					}
				});
				refreshers.put(key, v -> slider.setValue(clamp((Integer) v, range.min(), range.max())));
				JPanel pair = new JPanel(new BorderLayout(4, 0));
				pair.add(slider, BorderLayout.CENTER);
				pair.add(shown, BorderLayout.EAST);
				pair.setPreferredSize(new Dimension(230, slider.getPreferredSize().height));
				control = pair;
			}
			else
			{
				JSpinner spinner = new JSpinner(new SpinnerNumberModel((int) (Integer) value, range == null ? Integer.MIN_VALUE : range.min(), range == null ? Integer.MAX_VALUE : range.max(), 1));
				spinner.addChangeListener(e -> set(key, spinner.getValue()));
				refreshers.put(key, spinner::setValue);
				control = spinner;
			}
		}
		else if (type.isEnum())
		{
			JComboBox<Object> combo = new JComboBox<>(type.getEnumConstants());
			combo.setSelectedItem(value);
			combo.addActionListener(e -> set(key, combo.getSelectedItem()));
			refreshers.put(key, combo::setSelectedItem);
			control = combo;
		}
		else if (type == Color.class)
		{
			JButton swatch = new JButton(" ");
			swatch.setBackground((Color) value);
			swatch.setOpaque(true);
			swatch.setPreferredSize(new Dimension(60, 24));
			swatch.addActionListener(e ->
			{
				Color chosen = JColorChooser.showDialog(swatch, item.name(), swatch.getBackground());
				if (chosen != null)
				{
					swatch.setBackground(chosen);
					set(key, chosen);
				}
			});
			refreshers.put(key, v -> swatch.setBackground((Color) v));
			control = swatch;
		}
		else if (type == Keybind.class)
		{
			// Keys are set from the sidebar; here they are shown so the panel lists every setting.
			JLabel shown = new JLabel(value.toString());
			shown.setEnabled(false);
			refreshers.put(key, v -> shown.setText(v.toString()));
			control = shown;
		}
		else if (type == String.class)
		{
			JTextField field = new JTextField((String) value, 18);
			field.addActionListener(e -> set(key, field.getText()));
			field.addFocusListener(new java.awt.event.FocusAdapter()
			{
				@Override
				public void focusLost(java.awt.event.FocusEvent e)
				{
					set(key, field.getText());
				}
			});
			refreshers.put(key, v -> field.setText((String) v));
			control = field;
		}
		else
		{
			return null;
		}
		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		east.add(control);
		row.add(east, BorderLayout.EAST);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}
}
