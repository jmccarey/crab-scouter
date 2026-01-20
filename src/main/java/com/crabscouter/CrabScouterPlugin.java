package com.crabscouter;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.client.game.WorldService;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "Crab Scouter",
	description = "Crowdsourced Gemstone Crab scouting - shows player counts, health, and location across worlds",
	tags = {"crab", "gemstone", "scouter", "crowdsource"}
)
public class CrabScouterPlugin extends Plugin
{
	private static final int GEMSTONE_CRAB_ID = 14779;
	private static final int CHUNK_NORTH = 4913;
	private static final int CHUNK_WEST = 4911;
	private static final int CHUNK_EAST = 5424;
	private static final String SERVER_URL = "wss://crab-scouter.josephpmccarey.workers.dev";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private WorldService worldService;

	@Inject
	private OkHttpClient okHttpClient;

	@Getter
	private final List<WorldData> worldDataList = new CopyOnWriteArrayList<>();

	private CrabScouterPanel panel;
	private NavigationButton navButton;
	private CrabScouterWebSocketClient webSocketClient;

	@Getter
	private boolean isReporter = false;

	@Getter
	private boolean isConnected = false;

	private NPC trackedCrab;
	private int lastHealthRatio = -1;
	private int lastPlayerCount = -1;
	private int currentChunk = -1;
	private boolean inCrabArea = false;
	private int ticksSinceLastReport = 0;
	private int ticksWithoutCrab = 0;

	// World hopping state
	private static final int HOP_MAX_ATTEMPTS = 3;
	private net.runelite.api.World quickHopTargetWorld;
	private int hopAttempts = 0;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("Crab Scouter started!");

		panel = new CrabScouterPanel(this);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/crab_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Crab Scouter")
			.icon(icon != null ? icon : new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))
			.priority(10)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		webSocketClient = new CrabScouterWebSocketClient(this, SERVER_URL, okHttpClient);
		webSocketClient.connect();
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("Crab Scouter stopped!");

		if (inCrabArea && webSocketClient != null)
		{
			webSocketClient.sendLeave(client.getWorld());
		}

		if (webSocketClient != null)
		{
			webSocketClient.close();
			webSocketClient = null;
		}

		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;

		trackedCrab = null;
		inCrabArea = false;
		isReporter = false;
		isConnected = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			if (inCrabArea && webSocketClient != null)
			{
				webSocketClient.sendLeave(client.getWorld());
			}
			inCrabArea = false;
			trackedCrab = null;
			isReporter = false;
			currentChunk = -1;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		handleHop();

		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		WorldPoint playerLocation = client.getLocalPlayer().getWorldLocation();
		int regionId = playerLocation.getRegionID();

		if (ticksSinceLastReport % 10 == 0)
		{
			log.debug("Player at region {} (x={}, y={})", regionId, playerLocation.getX(), playerLocation.getY());
		}

		boolean wasInCrabArea = inCrabArea;
		inCrabArea = (regionId == CHUNK_NORTH || regionId == CHUNK_WEST || regionId == CHUNK_EAST);
		currentChunk = inCrabArea ? regionId : -1;

		if (inCrabArea && !wasInCrabArea)
		{
			onEnterCrabArea();
		}
		else if (!inCrabArea && wasInCrabArea)
		{
			onLeaveCrabArea();
		}

		if (inCrabArea && isReporter)
		{
			ticksSinceLastReport++;
			checkAndReport();
		}
		else
		{
			ticksSinceLastReport++;
		}
	}

	private void onEnterCrabArea()
	{
		log.debug("Entered crab area in chunk {}", currentChunk);
		if (webSocketClient != null)
		{
			webSocketClient.sendJoin(client.getWorld(), currentChunk);
		}
		findCrab();
	}

	private void onLeaveCrabArea()
	{
		log.debug("Left crab area");
		if (webSocketClient != null)
		{
			webSocketClient.sendLeave(client.getWorld());
		}
		trackedCrab = null;
		isReporter = false;
		lastHealthRatio = -1;
		lastPlayerCount = -1;
		ticksSinceLastReport = 0;
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (!inCrabArea)
		{
			return;
		}

		NPC npc = event.getNpc();
		if (npc.getId() == GEMSTONE_CRAB_ID)
		{
			trackedCrab = npc;
			log.debug("Gemstone crab spawned by ID: {}", npc.getId());
			return;
		}
		
		String name = npc.getName();
		if (name != null && isGemstoneCrab(name))
		{
			trackedCrab = npc;
			log.debug("Gemstone crab spawned by name: {} (ID: {})", name, npc.getId());
		}
	}

	private boolean isGemstoneCrab(String name)
	{
		String lower = name.toLowerCase();
		return lower.contains("gemstone") && lower.contains("crab") && !lower.contains("shell");
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (trackedCrab != null && event.getNpc() == trackedCrab)
		{
			trackedCrab = null;
			log.debug("Gemstone crab despawned");
		}
	}

	private void findCrab()
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return;
		}

		int npcCount = 0;
		for (NPC npc : worldView.npcs())
		{
			npcCount++;
			if (npc.getId() == GEMSTONE_CRAB_ID)
			{
				trackedCrab = npc;
				log.debug("Found gemstone crab by ID: {}", npc.getId());
				return;
			}
			
			String name = npc.getName();
			if (name != null && isGemstoneCrab(name))
			{
				trackedCrab = npc;
				log.debug("Found gemstone crab by name: {} (ID: {})", name, npc.getId());
				return;
			}
		}
		
		log.debug("Gemstone crab not found in {} NPCs", npcCount);
	}

	private void checkAndReport()
	{
		if (!isReporter || webSocketClient == null || currentChunk == -1)
		{
			return;
		}

		if (trackedCrab == null)
		{
			findCrab();
		}

		if (trackedCrab == null)
		{
			ticksWithoutCrab++;
			if (ticksWithoutCrab >= 50)
			{
				log.debug("Can't see crab for {} ticks, resigning as reporter", ticksWithoutCrab);
				webSocketClient.sendResign(client.getWorld());
				isReporter = false;
				ticksWithoutCrab = 0;
			}
			return;
		}

		ticksWithoutCrab = 0;

		int crabChunk = trackedCrab.getWorldLocation().getRegionID();
		if (crabChunk != currentChunk)
		{
			log.debug("Crab is in different chunk ({}) than player ({})", crabChunk, currentChunk);
			trackedCrab = null;
			return;
		}

		int currentHealth = getHealthPercent();
		int[] playerCounts = countPlayers();
		int totalPlayers = playerCounts[0];
		int attackingPlayers = playerCounts[1];

		boolean healthChanged = Math.abs(currentHealth - lastHealthRatio) > 5;
		boolean playersChanged = totalPlayers != lastPlayerCount;
		boolean heartbeat = ticksSinceLastReport >= 100;

		if (healthChanged || playersChanged || heartbeat)
		{
			log.debug("Sending report: world={}, chunk={}, health={}, total={}, attacking={}, reason={}",
				client.getWorld(), crabChunk, currentHealth, totalPlayers, attackingPlayers,
				heartbeat ? "heartbeat" : (healthChanged ? "health" : "players"));
			webSocketClient.sendReport(client.getWorld(), crabChunk, currentHealth, totalPlayers, attackingPlayers);
			lastHealthRatio = currentHealth;
			lastPlayerCount = totalPlayers;
			ticksSinceLastReport = 0;
		}
	}

	private int getHealthPercent()
	{
		if (trackedCrab == null)
		{
			log.debug("getHealthPercent: trackedCrab is null");
			return 100;
		}

		int ratio = trackedCrab.getHealthRatio();
		int scale = trackedCrab.getHealthScale();

		log.debug("getHealthPercent: ratio={}, scale={}", ratio, scale);

		if (ratio == -1 || scale == -1)
		{
			return 100;
		}

		return (int) ((ratio / (double) scale) * 100);
	}

	private int[] countPlayers()
	{
		int totalPlayers = 0;
		int attackingPlayers = 0;
		
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return new int[]{0, 0};
		}

		for (Player player : worldView.players())
		{
			if (player == null)
			{
				continue;
			}
			
			totalPlayers++;

			if (trackedCrab != null)
			{
				Actor target = player.getInteracting();
				if (target == trackedCrab)
				{
					attackingPlayers++;
				}
			}
		}
		
		log.debug("countPlayers: total={}, attacking={}", totalPlayers, attackingPlayers);
		
		return new int[]{totalPlayers, attackingPlayers};
	}

	public void onWebSocketConnected()
	{
		isConnected = true;
		log.debug("WebSocket connected");
		updatePanel();

		clientThread.invokeLater(() ->
		{
			if (inCrabArea && client.getGameState() == GameState.LOGGED_IN)
			{
				log.debug("Reconnected while in crab area, resending join");
				webSocketClient.sendJoin(client.getWorld(), currentChunk);
			}
		});
	}

	public void onWebSocketDisconnected()
	{
		isConnected = false;
		isReporter = false;
		log.debug("WebSocket disconnected");
		updatePanel();
	}

	public void onRoleAssigned(boolean reporter)
	{
		isReporter = reporter;
		log.debug("Role assigned: {}", reporter ? "reporter" : "listener");

		if (isReporter && inCrabArea)
		{
			clientThread.invokeLater(() ->
			{
				int currentHealth = getHealthPercent();
				int[] playerCounts = countPlayers();
				webSocketClient.sendReport(client.getWorld(), currentChunk, currentHealth, playerCounts[0], playerCounts[1]);
				lastHealthRatio = currentHealth;
				lastPlayerCount = playerCounts[0];
				ticksSinceLastReport = 0;
			});
		}
	}

	public void onWorldsUpdate(List<WorldData> worlds)
	{
		log.debug("onWorldsUpdate called with {} worlds", worlds.size());
		worldDataList.clear();
		worldDataList.addAll(worlds);
		updatePanel();
	}

	private void updatePanel()
	{
		log.debug("updatePanel called, panel={}, worldDataList size={}", panel != null, worldDataList.size());
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::update);
		}
	}

	public void hopToWorld(int worldNumber)
	{
		log.debug("hopToWorld called for world {}", worldNumber);

		if (client.getGameState() != GameState.LOGGED_IN)
		{
			log.warn("Cannot hop - not logged in (state: {})", client.getGameState());
			return;
		}

		int currentWorld = client.getWorld();
		if (currentWorld == worldNumber)
		{
			log.debug("Already on world {}", worldNumber);
			return;
		}

		WorldResult worldResult = worldService.getWorlds();
		if (worldResult == null)
		{
			log.warn("World list not loaded yet");
			return;
		}

		World targetWorld = worldResult.findWorld(worldNumber);
		if (targetWorld == null)
		{
			log.warn("Could not find world {} in world list", worldNumber);
			return;
		}

		log.info("Hopping to world {} (current: {})", worldNumber, currentWorld);
		net.runelite.api.World rsWorld = client.createWorld();
		rsWorld.setActivity(targetWorld.getActivity());
		rsWorld.setAddress(targetWorld.getAddress());
		rsWorld.setId(targetWorld.getId());
		rsWorld.setPlayerCount(targetWorld.getPlayers());
		rsWorld.setLocation(targetWorld.getLocation());
		rsWorld.setTypes(WorldUtil.toWorldTypes(targetWorld.getTypes()));

		// Set target world - actual hop happens in handleHop() on game tick
		quickHopTargetWorld = rsWorld;
		hopAttempts = 0;
	}

	private void handleHop()
	{
		if (quickHopTargetWorld == null)
		{
			return;
		}

		// Check if world switcher is open
		if (client.getWidget(ComponentID.WORLD_SWITCHER_WORLD_LIST) == null)
		{
			// Open the world hopper first
			client.openWorldHopper();

			if (++hopAttempts >= HOP_MAX_ATTEMPTS)
			{
				log.warn("Failed to open world switcher after {} attempts", hopAttempts);
				resetHop();
			}
		}
		else
		{
			// World switcher is open, perform the hop
			client.hopToWorld(quickHopTargetWorld);
			resetHop();
		}
	}

	private void resetHop()
	{
		quickHopTargetWorld = null;
		hopAttempts = 0;
	}
}
