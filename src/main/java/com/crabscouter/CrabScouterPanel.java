package com.crabscouter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.Box;
import javax.swing.BoxLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

public class CrabScouterPanel extends PluginPanel
{
	private enum SortColumn
	{
		WORLD, CHUNK, HEALTH, PLAYERS
	}

	private final CrabScouterPlugin plugin;
	private final JPanel worldListPanel;
	private final JLabel statusLabel;
	private final JLabel roleLabel;
	private final JLabel connectionIndicator;
	private final JLabel[] headerLabels = new JLabel[4];

	private SortColumn sortColumn = SortColumn.WORLD;
	private boolean sortAscending = true;

	public CrabScouterPanel(CrabScouterPlugin plugin)
	{
		super(false);
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(10, 10, 10, 10));

		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(0, 0, 10, 0));

		JLabel titleLabel = new JLabel("Crab Scouter");
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(Color.WHITE);

		connectionIndicator = new JLabel("●");
		connectionIndicator.setForeground(Color.RED);
		connectionIndicator.setFont(FontManager.getRunescapeBoldFont());

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.setBorder(new EmptyBorder(0, 0, 8, 0));
		titleRow.add(titleLabel, BorderLayout.WEST);
		titleRow.add(connectionIndicator, BorderLayout.EAST);

		headerPanel.add(titleRow, BorderLayout.NORTH);

		JPanel columnHeaders = createHeaderRow();
		headerPanel.add(columnHeaders, BorderLayout.SOUTH);

		add(headerPanel, BorderLayout.NORTH);

		worldListPanel = new JPanel();
		worldListPanel.setLayout(new BoxLayout(worldListPanel, BoxLayout.Y_AXIS));
		worldListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scrollPane = new JScrollPane(worldListPanel);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));

		add(scrollPane, BorderLayout.CENTER);

		JPanel footerPanel = new JPanel(new BorderLayout());
		footerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footerPanel.setBorder(new EmptyBorder(10, 0, 0, 0));

		statusLabel = new JLabel("Connecting...");
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		footerPanel.add(statusLabel, BorderLayout.WEST);

		roleLabel = new JLabel("");
		roleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		roleLabel.setFont(FontManager.getRunescapeSmallFont());
		footerPanel.add(roleLabel, BorderLayout.SOUTH);

		add(footerPanel, BorderLayout.SOUTH);

		update();
	}

	private JPanel createHeaderRow()
	{
		JPanel row = new JPanel(new GridLayout(1, 4, 5, 1));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(5, 5, 5, 5));

		headerLabels[0] = createSortableHeaderLabel("World", SortColumn.WORLD);
		headerLabels[1] = createSortableHeaderLabel("Chunk", SortColumn.CHUNK);
		headerLabels[2] = createSortableHeaderLabel("HP", SortColumn.HEALTH);
		headerLabels[3] = createSortableHeaderLabel("#", SortColumn.PLAYERS);

		for (JLabel label : headerLabels)
		{
			row.add(label);
		}

		updateHeaderLabels();
		return row;
	}

	private JLabel createSortableHeaderLabel(String text, SortColumn column)
	{
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.putClientProperty("column", column);
		label.putClientProperty("baseText", text);

		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onHeaderClick(column);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(column == sortColumn ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
			}
		});

		return label;
	}

	private void onHeaderClick(SortColumn column)
	{
		if (sortColumn == column)
		{
			sortAscending = !sortAscending;
		}
		else
		{
			sortColumn = column;
			sortAscending = true;
		}
		updateHeaderLabels();
		update();
	}

	private void updateHeaderLabels()
	{
		String[] baseTexts = {"World", "Chunk", "HP", "#"};
		SortColumn[] columns = {SortColumn.WORLD, SortColumn.CHUNK, SortColumn.HEALTH, SortColumn.PLAYERS};

		for (int i = 0; i < headerLabels.length; i++)
		{
			String text = baseTexts[i];
			if (columns[i] == sortColumn)
			{
				text += sortAscending ? " ↑" : " ↓";
				headerLabels[i].setForeground(Color.WHITE);
			}
			else
			{
				headerLabels[i].setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			}
			headerLabels[i].setText(text);
		}
	}

	private List<WorldData> getSortedWorlds(List<WorldData> worlds)
	{
		List<WorldData> sorted = new ArrayList<>();
		for (WorldData data : worlds)
		{
			if (data.isFresh())
			{
				sorted.add(data);
			}
		}

		Comparator<WorldData> comparator;
		switch (sortColumn)
		{
			case CHUNK:
				comparator = Comparator.comparing(WorldData::getChunkName);
				break;
			case HEALTH:
				comparator = Comparator.comparingInt(WorldData::getHealth);
				break;
			case PLAYERS:
				comparator = Comparator.comparingInt(WorldData::getTotalPlayers);
				break;
			case WORLD:
			default:
				comparator = Comparator.comparingInt(WorldData::getWorld);
				break;
		}

		if (!sortAscending)
		{
			comparator = comparator.reversed();
		}

		sorted.sort(comparator);
		return sorted;
	}

	public void update()
	{
		worldListPanel.removeAll();

		boolean connected = plugin.isConnected();
		connectionIndicator.setForeground(connected ? Color.GREEN : Color.RED);
		connectionIndicator.setToolTipText(connected ? "Connected" : "Disconnected");

		List<WorldData> worlds = plugin.getWorldDataList();
		List<WorldData> sortedWorlds = getSortedWorlds(worlds);

		if (worlds.isEmpty())
		{
			JLabel emptyLabel = new JLabel("No crab data available");
			emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			emptyLabel.setAlignmentX(CENTER_ALIGNMENT);
			worldListPanel.add(Box.createVerticalGlue());
			worldListPanel.add(emptyLabel);
			worldListPanel.add(Box.createVerticalGlue());
		}
		else if (sortedWorlds.isEmpty())
		{
			JLabel staleLabel = new JLabel("All data stale");
			staleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			staleLabel.setAlignmentX(CENTER_ALIGNMENT);
			worldListPanel.add(Box.createVerticalGlue());
			worldListPanel.add(staleLabel);
			worldListPanel.add(Box.createVerticalGlue());
		}
		else
		{
			for (WorldData data : sortedWorlds)
			{
				worldListPanel.add(createWorldRow(data));
			}
		}

		int scoutCount = worlds.size();
		StringBuilder status = new StringBuilder();
		if (scoutCount == 0)
		{
			status.append("No worlds are reporting data");
		}
		else
		{
			status.append(scoutCount)
				.append(scoutCount == 1 ? " world is" : " worlds are")
				.append(" reporting data");
		}
		statusLabel.setText(status.toString());
		roleLabel.setText(plugin.isReporter() ? "You are reporting for your world." : "");	

		revalidate();
		repaint();
	}

	private JPanel createWorldRow(WorldData data)
	{
		JPanel row = new JPanel(new GridLayout(1, 4, 5, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(4, 5, 4, 5));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

		JLabel worldLabel = new JLabel(String.valueOf(data.getWorld()), SwingConstants.CENTER);
		worldLabel.setForeground(Color.WHITE);

		JLabel chunkLabel = new JLabel(data.getChunkName(), SwingConstants.CENTER);
		chunkLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JLabel healthLabel = new JLabel(data.getHealth() + "%", SwingConstants.CENTER);
		healthLabel.setForeground(getHealthColor(data.getHealth()));

		String playerText = data.getAttackingPlayers() + "/" + data.getTotalPlayers();
		JLabel playersLabel = new JLabel(playerText, SwingConstants.CENTER);
		playersLabel.setForeground(getPlayerCountColor(data.getTotalPlayers()));
		playersLabel.setToolTipText(data.getAttackingPlayers() + " attacking, " + data.getTotalPlayers() + " total");

		row.add(worldLabel);
		row.add(chunkLabel);
		row.add(healthLabel);
		row.add(playersLabel);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					plugin.hopToWorld(data.getWorld());
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		return row;
	}

	private Color getHealthColor(int health)
	{
		if (health > 66)
		{
			return Color.GREEN;
		}
		else if (health > 33)
		{
			return Color.YELLOW;
		}
		else
		{
			return Color.RED;
		}
	}

	private Color getPlayerCountColor(int count)
	{
		if (count <= 5)
		{
			return Color.GREEN;
		}
		else if (count <= 15)
		{
			return Color.YELLOW;
		}
		else
		{
			return Color.ORANGE;
		}
	}
}
