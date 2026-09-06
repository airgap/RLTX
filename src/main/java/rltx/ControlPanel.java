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
	private final Presets presets;
	private final AreaRules areas;
	private final java.util.function.Supplier<net.runelite.api.coords.WorldPoint> currentPosition;
	private final Consumer<List<int[]>> preview;
	private final Cinema cinema;
	private final CinemaPaths paths;

	/** What the cinema tab can do; the plugin implements it over the same actions as the keys. */
	interface Cinema
	{
		int keyframes();

		String state();

		void record();

		void clear();

		void render();

		void preview();

		void stop();

		List<double[]> export();

		void load(List<double[]> keys);
	}
	private final Map<String, Consumer<Object>> refreshers = new HashMap<>();
	private JFrame frame;
	private boolean updating;

	ControlPanel(ConfigManager configManager, RltxConfig config, Presets presets, AreaRules areas, java.util.function.Supplier<net.runelite.api.coords.WorldPoint> currentPosition,
		Consumer<List<int[]>> preview, Cinema cinema, CinemaPaths paths)
	{
		this.configManager = configManager;
		this.config = config;
		this.presets = presets;
		this.areas = areas;
		this.currentPosition = currentPosition;
		this.preview = preview;
		this.cinema = cinema;
		this.paths = paths;
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
				preview.accept(null);
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

		tabs.addTab("Presets", presetsTab());
		tabs.addTab("Areas", areasTab());
		tabs.addTab("Cinema", cinemaTab());

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
			// Click, then press the key with any modifiers; Escape keeps the old key, Backspace clears it.
			JButton keyButton = new JButton(value.toString());
			keyButton.setFocusable(true);
			keyButton.addActionListener(e ->
			{
				keyButton.setText("Press a key…");
				keyButton.requestFocusInWindow();
			});
			keyButton.addKeyListener(new java.awt.event.KeyAdapter()
			{
				@Override
				public void keyPressed(java.awt.event.KeyEvent e)
				{
					if (!"Press a key…".equals(keyButton.getText()))
					{
						return;
					}
					int code = e.getKeyCode();
					if (code == java.awt.event.KeyEvent.VK_SHIFT || code == java.awt.event.KeyEvent.VK_CONTROL || code == java.awt.event.KeyEvent.VK_ALT || code == java.awt.event.KeyEvent.VK_META)
					{
						return;
					}
					e.consume();
					if (code == java.awt.event.KeyEvent.VK_ESCAPE)
					{
						keyButton.setText(current(key).toString());
						return;
					}
					Keybind chosen = code == java.awt.event.KeyEvent.VK_BACK_SPACE ? Keybind.NOT_SET : new Keybind(code, e.getModifiersEx());
					keyButton.setText(chosen.toString());
					set(key, chosen);
				}
			});
			refreshers.put(key, v -> keyButton.setText(v.toString()));
			control = keyButton;
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

	// Whole settings saved by name to files, or carried on the clipboard.
	private JComponent presetsTab()
	{
		JPanel page = new JPanel(new GridBagLayout());
		page.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(4, 0, 4, 0);
		JLabel status = new JLabel(" ");
		JComboBox<String> saved = new JComboBox<>(presets.names().toArray(new String[0]));
		saved.setEditable(true);
		Runnable refreshNames = () ->
		{
			Object keep = saved.getEditor().getItem();
			saved.removeAllItems();
			for (String name : presets.names())
			{
				saved.addItem(name);
			}
			saved.getEditor().setItem(keep);
		};
		page.add(new JLabel("Preset name, or pick a saved one:"), c);
		++c.gridy;
		page.add(saved, c);
		++c.gridy;
		JPanel buttons = new JPanel(new java.awt.GridLayout(2, 2, 6, 6));
		buttons.add(button("Save to file", () ->
		{
			String name = String.valueOf(saved.getEditor().getItem()).trim();
			if (name.isEmpty())
			{
				status.setText("Give the preset a name first.");
				return;
			}
			presets.save(name, presets.capture());
			refreshNames.run();
			status.setText("Saved " + name + " in " + Presets.DIR);
		}, status));
		buttons.add(button("Load from file", () ->
		{
			String name = String.valueOf(saved.getEditor().getItem()).trim();
			List<String> unknown = presets.apply(presets.load(name));
			status.setText("Applied " + name + (unknown.isEmpty() ? "" : "; skipped settings this build does not have: " + unknown));
		}, status));
		buttons.add(button("Copy to clipboard", () ->
		{
			presets.copy(presets.capture());
			status.setText("Settings copied as JSON.");
		}, status));
		buttons.add(button("Paste from clipboard", () ->
		{
			Map<String, String> values = presets.paste();
			if (values == null)
			{
				status.setText("The clipboard holds no settings.");
				return;
			}
			List<String> unknown = presets.apply(values);
			status.setText("Applied " + (values.size() - unknown.size()) + " settings from the clipboard" + (unknown.isEmpty() ? "." : "; skipped " + unknown));
		}, status));
		page.add(buttons, c);
		++c.gridy;
		page.add(button("Delete file", () ->
		{
			String name = String.valueOf(saved.getEditor().getItem()).trim();
			presets.delete(name);
			refreshNames.run();
			status.setText("Deleted " + name);
		}, status), c);
		++c.gridy;
		page.add(status, c);
		++c.gridy;
		c.weighty = 1;
		page.add(Box.createVerticalGlue(), c);
		return page;
	}

	// Settings bound to map regions: what differs from a base preset while standing in the area.
	private JComponent areasTab()
	{
		JPanel page = new JPanel(new GridBagLayout());
		page.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(4, 0, 4, 0);
		JLabel status = new JLabel(" ");
		javax.swing.DefaultListModel<AreaRules.Rule> model = new javax.swing.DefaultListModel<>();
		for (AreaRules.Rule rule : areas.rules())
		{
			model.addElement(rule);
		}
		javax.swing.JList<AreaRules.Rule> list = new javax.swing.JList<>(model);
		list.setVisibleRowCount(6);
		JTextField name = new JTextField(18);
		JTextField regions = new JTextField(18);
		JTextField polygons = new JTextField(18);
		JCheckBox misty = new JCheckBox("Misty ground: swamps and graveyards, wherever the mist grid reaches");
		JComboBox<String> base = new JComboBox<>(presets.names().toArray(new String[0]));
		list.addListSelectionListener(e ->
		{
			AreaRules.Rule rule = list.getSelectedValue();
			if (rule != null)
			{
				preview.accept(rule.polygons);
				misty.setSelected(rule.misty);
				name.setText(rule.name);
				StringBuilder text = new StringBuilder();
				for (Integer region : rule.regions)
				{
					text.append(text.length() == 0 ? "" : ", ").append(region);
				}
				regions.setText(text.toString());
				StringBuilder shapes = new StringBuilder();
				for (int[] polygon : rule.polygons)
				{
					if (shapes.length() > 0)
					{
						shapes.append("; ");
					}
					for (int i = 1; i + 1 < polygon.length; i += 2)
					{
						shapes.append(i == 1 ? "" : " ").append(polygon[i]).append(",").append(polygon[i + 1]);
					}
					if (polygon[0] >= 0)
					{
						shapes.append(" @").append(polygon[0]);
					}
				}
				polygons.setText(shapes.toString());
			}
		});
		JCheckBox enabled = new JCheckBox("Apply area settings", config.areaSettings());
		enabled.addActionListener(e -> set("areaSettings", enabled.isSelected()));
		refreshers.put("areaSettings", v -> enabled.setSelected((Boolean) v));
		page.add(enabled, c);
		++c.gridy;
		JLabel where = new JLabel(" ");
		new javax.swing.Timer(500, e ->
		{
			net.runelite.api.coords.WorldPoint here = currentPosition.get();
			where.setText(here == null ? "Not in the world." : "You are at " + here.getX() + ", " + here.getY() + " on plane " + here.getPlane() + ", region " + here.getRegionID() + ".");
		}).start();
		page.add(where, c);
		++c.gridy;
		JLabel help = new JLabel("<html>Bound the area with polygons: walk its outline, marking a corner at each turn, and close the shape; or type corners as x,y pairs separated by spaces, an optional @plane at the end, polygons separated by semicolons. Without polygons the map regions are used. Then set the look you want, choose the preset holding your usual settings as the base, and save: only what differs is kept, and it is put back when you leave.</html>");
		page.add(help, c);
		++c.gridy;
		page.add(new JScrollPane(list), c);
		++c.gridy;
		page.add(labelled("Area name", name), c);
		++c.gridy;
		JPanel regionRow = new JPanel(new BorderLayout(6, 0));
		regionRow.add(regions, BorderLayout.CENTER);
		regionRow.add(button("Add current region", () ->
		{
			net.runelite.api.coords.WorldPoint here = currentPosition.get();
			if (here == null)
			{
				status.setText("Not in the world.");
				return;
			}
			int region = here.getRegionID();
			String text = regions.getText().trim();
			regions.setText(text.isEmpty() ? Integer.toString(region) : text + ", " + region);
		}, status), BorderLayout.EAST);
		page.add(labelled("Map regions", regionRow), c);
		++c.gridy;
		StringBuilder outline = new StringBuilder();
		JPanel shapeRow = new JPanel(new BorderLayout(6, 0));
		shapeRow.add(polygons, BorderLayout.CENTER);
		JPanel cornerButtons = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
		cornerButtons.add(button("Mark corner", () ->
		{
			net.runelite.api.coords.WorldPoint here = currentPosition.get();
			if (here == null)
			{
				status.setText("Not in the world.");
				return;
			}
			outline.append(outline.length() == 0 ? "" : " ").append(here.getX()).append(",").append(here.getY());
			int corners = outline.toString().split(" ").length;
			if (corners >= 2)
			{
				// Shows the outline so far on the ground, closed back to its first corner.
				String[] marked = outline.toString().split(" ");
				int[] partial = new int[1 + marked.length * 2];
				partial[0] = -1;
				for (int i = 0; i < marked.length; ++i)
				{
					String[] xy = marked[i].split(",");
					partial[1 + i * 2] = Integer.parseInt(xy[0]);
					partial[2 + i * 2] = Integer.parseInt(xy[1]);
				}
				List<int[]> shown = new ArrayList<>();
				shown.add(partial);
				preview.accept(shown);
			}
			status.setText(corners + (corners == 1 ? " corner marked at " : " corners marked, last at ") + here.getX() + ", " + here.getY() + ". Close the shape when the outline is walked.");
		}, status));
		cornerButtons.add(button("Close shape", () ->
		{
			if (outline.toString().split(" ").length < 3)
			{
				status.setText("A polygon needs at least three corners.");
				return;
			}
			String text = polygons.getText().trim();
			polygons.setText(text.isEmpty() ? outline.toString() : text + "; " + outline);
			outline.setLength(0);
			status.setText("Polygon added.");
		}, status));
		shapeRow.add(cornerButtons, BorderLayout.EAST);
		page.add(labelled("Polygons", shapeRow), c);
		++c.gridy;
		page.add(misty, c);
		++c.gridy;
		page.add(labelled("Base preset", base), c);
		++c.gridy;
		JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 6, 6));
		buttons.add(button("Save differences from base", () ->
		{
			String areaName = name.getText().trim();
			if (areaName.isEmpty() || base.getSelectedItem() == null)
			{
				status.setText("Name the area and choose a base preset.");
				return;
			}
			AreaRules.Rule rule = new AreaRules.Rule();
			rule.name = areaName;
			rule.misty = misty.isSelected();
			for (String part : regions.getText().split("[,\\s]+"))
			{
				if (!part.isEmpty())
				{
					rule.regions.add(Integer.parseInt(part));
				}
			}
			for (String shape : polygons.getText().split(";"))
			{
				String text = shape.trim();
				if (text.isEmpty())
				{
					continue;
				}
				int plane = -1;
				int at = text.indexOf('@');
				if (at >= 0)
				{
					plane = Integer.parseInt(text.substring(at + 1).trim());
					text = text.substring(0, at).trim();
				}
				String[] corners = text.split("\\s+");
					if (corners.length < 3)
				{
					throw new NumberFormatException("A polygon needs at least three corners: " + shape.trim());
				}
				int[] polygon = new int[1 + corners.length * 2];
				polygon[0] = plane;
				for (int i = 0; i < corners.length; ++i)
				{
					String[] xy = corners[i].split(",");
					polygon[1 + i * 2] = Integer.parseInt(xy[0].trim());
					polygon[2 + i * 2] = Integer.parseInt(xy[1].trim());
				}
				rule.polygons.add(polygon);
			}
			rule.overrides = Presets.diff(presets.load(String.valueOf(base.getSelectedItem())), presets.capture());
			areas.put(rule);
			areas.save();
			model.removeAllElements();
			for (AreaRules.Rule r : areas.rules())
			{
				model.addElement(r);
			}
			status.setText(areaName + ": " + rule.overrides.size() + " settings differ from the base.");
		}, status));
		buttons.add(button("Remove area", () ->
		{
			AreaRules.Rule rule = list.getSelectedValue();
			if (rule == null)
			{
				return;
			}
			areas.remove(rule);
			areas.save();
			model.removeAllElements();
			for (AreaRules.Rule r : areas.rules())
			{
				model.addElement(r);
			}
			status.setText("Removed " + rule.name);
		}, status));
		page.add(buttons, c);
		++c.gridy;
		java.io.File repository = AreaRules.repository();
		JButton bundle = button("Save selected area into the repository as a bundled default", () ->
		{
			AreaRules.Rule rule = list.getSelectedValue();
			if (rule == null)
			{
				status.setText("Select an area first.");
				return;
			}
			areas.saveBundled(repository, rule);
			status.setText("Written to src/main/resources/rltx/areas in " + repository.getName() + "; commit it to ship it.");
		}, status);
		bundle.setEnabled(repository != null);
		bundle.setToolTipText(repository == null ? "Only available when running from a source checkout" : repository.getPath());
		page.add(bundle, c);
		++c.gridy;
		page.add(status, c);
		++c.gridy;
		c.weighty = 1;
		page.add(Box.createVerticalGlue(), c);
		return page;
	}

	// The cinema's actions as buttons, and its paths saved by name.
	private JComponent cinemaTab()
	{
		JPanel page = new JPanel(new GridBagLayout());
		page.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(4, 0, 4, 0);
		JLabel status = new JLabel(" ");
		JLabel state = new JLabel(cinema.state());
		new javax.swing.Timer(500, e -> state.setText(cinema.state())).start();
		page.add(state, c);
		++c.gridy;
		JPanel actions = new JPanel(new java.awt.GridLayout(2, 3, 6, 6));
		actions.add(button("Record keyframe", cinema::record, status));
		actions.add(button("Clear keyframes", cinema::clear, status));
		actions.add(button("Stop", cinema::stop, status));
		actions.add(button("Preview", cinema::preview, status));
		actions.add(button("Render", cinema::render, status));
		page.add(actions, c);
		++c.gridy;
		JComboBox<String> saved = new JComboBox<>(paths.names().toArray(new String[0]));
		saved.setEditable(true);
		Runnable refreshNames = () ->
		{
			Object keep = saved.getEditor().getItem();
			saved.removeAllItems();
			for (String name : paths.names())
			{
				saved.addItem(name);
			}
			saved.getEditor().setItem(keep);
		};
		page.add(new JLabel("Path name, or pick a saved one:"), c);
		++c.gridy;
		page.add(saved, c);
		++c.gridy;
		JPanel files = new JPanel(new java.awt.GridLayout(1, 3, 6, 6));
		files.add(button("Save path", () ->
		{
			String name = String.valueOf(saved.getEditor().getItem()).trim();
			if (name.isEmpty())
			{
				status.setText("Give the path a name first.");
				return;
			}
			paths.save(name, cinema.export());
			refreshNames.run();
			status.setText("Saved " + name + " with " + cinema.keyframes() + " keyframes.");
		}, status));
		files.add(button("Load path", () ->
		{
			String name = String.valueOf(saved.getEditor().getItem()).trim();
			List<double[]> keys = paths.load(name);
			cinema.load(keys);
			status.setText("Loaded " + name + ": " + keys.size() + " keyframes.");
		}, status));
		files.add(button("Delete path", () ->
		{
			String name = String.valueOf(saved.getEditor().getItem()).trim();
			paths.delete(name);
			refreshNames.run();
			status.setText("Deleted " + name);
		}, status));
		page.add(files, c);
		++c.gridy;
		page.add(status, c);
		++c.gridy;
		c.weighty = 1;
		page.add(Box.createVerticalGlue(), c);
		return page;
	}

	private interface Action
	{
		void run() throws java.io.IOException;
	}

	// File and clipboard failures are shown on the tab rather than thrown into Swing.
	private static JButton button(String label, Action action, JLabel status)
	{
		JButton button = new JButton(label);
		button.addActionListener(e ->
		{
			try
			{
				action.run();
			}
			catch (java.io.IOException | NumberFormatException ex)
			{
				status.setText(ex.getMessage());
			}
		});
		return button;
	}

	private static JComponent labelled(String label, JComponent control)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		JLabel text = new JLabel(label);
		text.setPreferredSize(new Dimension(90, text.getPreferredSize().height));
		row.add(text, BorderLayout.WEST);
		row.add(control, BorderLayout.CENTER);
		return row;
	}
}
