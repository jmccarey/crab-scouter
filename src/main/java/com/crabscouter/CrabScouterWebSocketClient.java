package com.crabscouter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

@Slf4j
public class CrabScouterWebSocketClient extends WebSocketListener
{
	private static final int RECONNECT_DELAY_SECONDS = 5;

	private final CrabScouterPlugin plugin;
	private final String serverUrl;
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;

	private WebSocket webSocket;
	private boolean shouldReconnect = true;
	private boolean isConnecting = false;

	public CrabScouterWebSocketClient(CrabScouterPlugin plugin, String serverUrl)
	{
		this.plugin = plugin;
		this.serverUrl = serverUrl;
		this.httpClient = new OkHttpClient.Builder()
			.pingInterval(30, TimeUnit.SECONDS)
			.build();
		this.gson = new Gson();
		this.executor = Executors.newSingleThreadScheduledExecutor();
	}

	public void connect()
	{
		if (isConnecting || webSocket != null)
		{
			return;
		}

		isConnecting = true;
		shouldReconnect = true;

		Request request = new Request.Builder()
			.url(serverUrl)
			.build();

		webSocket = httpClient.newWebSocket(request, this);
	}

	public void close()
	{
		shouldReconnect = false;
		executor.shutdown();

		if (webSocket != null)
		{
			webSocket.close(1000, "Plugin shutdown");
			webSocket = null;
		}
	}

	public void sendJoin(int world, int chunk)
	{
		if (webSocket == null)
		{
			return;
		}

		JsonObject message = new JsonObject();
		message.addProperty("type", "join");
		message.addProperty("world", world);
		message.addProperty("chunk", chunk);

		webSocket.send(gson.toJson(message));
		log.debug("Sent join message for world {} chunk {}", world, chunk);
	}

	public void sendLeave(int world)
	{
		if (webSocket == null)
		{
			return;
		}

		JsonObject message = new JsonObject();
		message.addProperty("type", "leave");
		message.addProperty("world", world);

		webSocket.send(gson.toJson(message));
		log.debug("Sent leave message for world {}", world);
	}

	public void sendReport(int world, int chunk, int health, int totalPlayers, int attackingPlayers)
	{
		if (webSocket == null)
		{
			return;
		}

		JsonObject message = new JsonObject();
		message.addProperty("type", "report");
		message.addProperty("world", world);
		message.addProperty("chunk", chunk);
		message.addProperty("health", health);
		message.addProperty("totalPlayers", totalPlayers);
		message.addProperty("attackingPlayers", attackingPlayers);

		webSocket.send(gson.toJson(message));
		log.debug("Sent report: world={} chunk={} health={} total={} attacking={}", world, chunk, health, totalPlayers, attackingPlayers);
	}

	public void sendResign(int world)
	{
		if (webSocket == null)
		{
			return;
		}

		JsonObject message = new JsonObject();
		message.addProperty("type", "resign");
		message.addProperty("world", world);

		webSocket.send(gson.toJson(message));
		log.debug("Sent resign message for world {}", world);
	}

	@Override
	public void onOpen(WebSocket webSocket, Response response)
	{
		isConnecting = false;
		log.info("WebSocket connected to {}", serverUrl);
		plugin.onWebSocketConnected();
	}

	@Override
	public void onMessage(WebSocket webSocket, String text)
	{
		log.debug("Received message: {}", text);
		try
		{
			JsonObject message = JsonParser.parseString(text).getAsJsonObject();
			String type = message.get("type").getAsString();

			switch (type)
			{
				case "role":
					boolean isReporter = message.get("isReporter").getAsBoolean();
					plugin.onRoleAssigned(isReporter);
					break;

				case "update":
					List<WorldData> worlds = parseWorldsUpdate(message);
					log.debug("Received update with {} worlds", worlds.size());
					plugin.onWorldsUpdate(worlds);
					break;

				default:
					log.warn("Unknown message type: {}", type);
			}
		}
		catch (Exception e)
		{
			log.error("Error parsing WebSocket message: {}", text, e);
		}
	}

	private List<WorldData> parseWorldsUpdate(JsonObject message)
	{
		List<WorldData> worlds = new ArrayList<>();
		JsonArray worldsArray = message.getAsJsonArray("worlds");

		for (int i = 0; i < worldsArray.size(); i++)
		{
			JsonObject worldObj = worldsArray.get(i).getAsJsonObject();
			worlds.add(new WorldData(
				worldObj.get("world").getAsInt(),
				worldObj.get("chunk").getAsInt(),
				worldObj.get("health").getAsInt(),
				worldObj.get("totalPlayers").getAsInt(),
				worldObj.get("attackingPlayers").getAsInt(),
				worldObj.get("lastUpdate").getAsLong()
			));
		}

		return worlds;
	}

	@Override
	public void onClosing(WebSocket webSocket, int code, String reason)
	{
		log.debug("WebSocket closing: {} - {}", code, reason);
	}

	@Override
	public void onClosed(WebSocket webSocket, int code, String reason)
	{
		this.webSocket = null;
		isConnecting = false;
		log.info("WebSocket closed: {} - {}", code, reason);
		plugin.onWebSocketDisconnected();

		scheduleReconnect();
	}

	@Override
	public void onFailure(WebSocket webSocket, Throwable t, Response response)
	{
		this.webSocket = null;
		isConnecting = false;
		log.error("WebSocket error", t);
		plugin.onWebSocketDisconnected();

		scheduleReconnect();
	}

	private void scheduleReconnect()
	{
		if (!shouldReconnect)
		{
			return;
		}

		log.debug("Scheduling reconnect in {} seconds", RECONNECT_DELAY_SECONDS);
		executor.schedule(this::connect, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
	}
}

