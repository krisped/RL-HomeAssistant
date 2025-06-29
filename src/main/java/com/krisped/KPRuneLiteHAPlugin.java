package com.krisped;

import com.google.inject.Provides;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
		name = "KP RuneLite HA",
		enabledByDefault = true
)
public class KPRuneLiteHAPlugin extends Plugin
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	@Inject private Client client;
	@Inject private KPRuneLiteConfig config;
	@Inject private ScheduledExecutorService executor;

	private final OkHttpClient httpClient = new OkHttpClient();
	private ScheduledFuture<?> heartbeatTask;
	private String lastUsername = null;

	@Provides
	KPRuneLiteConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(KPRuneLiteConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		super.startUp();
		log.info("KP RuneLite HA plugin startet");

		// Hvis vi allerede er inne i verden, send online med en gang
		if (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null)
		{
			log.debug("startUp(): LOGGED_IN + Player ok → sender online");
			sendStatus("online");
			scheduleHeartbeat();
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		super.shutDown();
		log.info("KP RuneLite HA plugin stoppet");
		if (heartbeatTask != null && !heartbeatTask.isCancelled())
		{
			heartbeatTask.cancel(false);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged evt)
	{
		GameState gs = evt.getGameState();
		log.debug("GameStateChanged: {}", gs);

		if (gs == GameState.LOGIN_SCREEN)
		{
			log.debug("→ LOGIN_SCREEN → sender offline");
			sendStatus("offline");
			cancelHeartbeat();
			return;
		}

		// Vi sender ONLINE først når spilleren faktisk spawner
		if (gs == GameState.LOGGED_IN)
		{
			log.debug("→ LOGGED_IN → venter på PlayerSpawned...");
		}
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned ev)
	{
		// Dette garanterer at LocalPlayer != null og fullt lastet
		log.debug("PlayerSpawned → sender online");
		sendStatus("online");
		scheduleHeartbeat();
	}

	private void scheduleHeartbeat()
	{
		// Avbryt gammel heartbeat
		cancelHeartbeat();
		// Send “online” hvert minutt for å holde HA-sensor fersk
		heartbeatTask = executor.scheduleAtFixedRate(
				() -> {
					log.debug("Heartbeat → sender online");
					sendStatus("online");
				},
				1,  // start etter 1 minutt
				1,  // gjenta hvert 1 minutt
				TimeUnit.MINUTES
		);
	}

	private void cancelHeartbeat()
	{
		if (heartbeatTask != null && !heartbeatTask.isCancelled())
		{
			heartbeatTask.cancel(false);
			heartbeatTask = null;
		}
	}

	private void sendStatus(String status)
	{
		// Finn brukernavn
		String username;
		if (client.getLocalPlayer() != null)
		{
			username = client.getLocalPlayer().getName().toLowerCase();
			lastUsername = username;
		}
		else if (lastUsername != null)
		{
			username = lastUsername;
		}
		else
		{
			log.warn("sendStatus: Fant ikke brukernavn – hopper over");
			return;
		}

		// Bygg URL og payload
		String base = config.haUrl().replaceAll("/+$", "");
		String url = base + "/api/events/kp_runelite_update_" + username;
		String json = "{\"status\":\"" + status + "\"}";
		RequestBody body = RequestBody.create(JSON, json);

		Request req = new Request.Builder()
				.url(url)
				.addHeader("Authorization", "Bearer " + config.haToken())
				.post(body)
				.build();

		httpClient.newCall(req).enqueue(new okhttp3.Callback()
		{
			@Override
			public void onFailure(okhttp3.Call call, IOException e)
			{
				log.warn("sendStatus: Klarte ikke kontakte HA: {}", e.toString());
			}
			@Override
			public void onResponse(okhttp3.Call call, Response response) throws IOException
			{
				log.debug("sendStatus: HA respons {}", response.code());
				response.close();
			}
		});
	}
}
