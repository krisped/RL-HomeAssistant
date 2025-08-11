package com.krisped;

import com.google.inject.Provides;
import com.krisped.status.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import okhttp3.*;

/**
 * KP RuneLite ↔ Home Assistant bridge inklusive «HA Button Check» via HA‑WS.
 */
@Slf4j
@PluginDescriptor(name = "KP Home Assistant", enabledByDefault = true)
public class HomeAssistantPlugin extends Plugin
{
	/* ───────── constants ───────── */
	private static final MediaType JSON =
			MediaType.get("application/json; charset=utf-8");
	private static final Pattern SANITIZE =
			Pattern.compile("[^a-z0-9]");

	/* ───────── injected ───────── */
	@Inject private Client                    client;
	@Inject private HomeAssistantConfig       config;
	@Inject private ScheduledExecutorService  executor;
	@Inject private EventBus                  eventBus;
	@Inject private OverlayManager            overlayManager;

	/* ───────── fields ───────── */
	private final OkHttpClient httpClient = new OkHttpClient();
	private ScheduledFuture<?> heartbeatTask;

	private HttpServer      keyServer;   // eksisterende keyboard‑HTTP
	private HaWsClient      haWsClient;  // ny WS‑klient
	private HAButtonOverlay overlay;     // overlay for ON/OFF
	private volatile boolean haButtonOn; // gjeldende state

	/* sensor‐toggles */
	private boolean lastShowHealth;
	private boolean lastShowPrayer;
	private boolean lastShowEnergy;
	private boolean lastShowCurrentWorld;
	private boolean lastShowSpecialAttack;
	private boolean lastShowCurrentSkill;
	private boolean lastShowIdleStatus;

	/* helper‑services */
	private CurrentOpponent     opponentService;
	private CurrentLocation     locationService;
	private SpecialAttackStatus specialService;
	private CurrentSkill        currentSkillService;
	private IdleTimer           idleTimer;

	/* ───────── API for overlay ───────── */
	public void setHaButtonState(boolean on) { haButtonOn = on; }
	public boolean isHaButtonOn()            { return haButtonOn; }

	/* ───────── config provider ───────── */
	@Provides
	HomeAssistantConfig provideConfig(ConfigManager mgr)
	{
		return mgr.getConfig(HomeAssistantConfig.class);
	}

	/* ───────── plugin lifecycle ───────── */
	@Override
	protected void startUp() throws Exception
	{
		super.startUp();

		/* les toggles */
		lastShowHealth        = config.showHealth();
		lastShowPrayer        = config.showPrayer();
		lastShowEnergy        = config.showEnergy();
		lastShowCurrentWorld  = config.showCurrentWorld();
		lastShowSpecialAttack = config.showSpecialAttack();
		lastShowCurrentSkill  = config.showCurrentSkill();
		lastShowIdleStatus    = config.showIdleStatus();

		/* init helper‑klasser */
		opponentService     = new CurrentOpponent(client, config, httpClient, this::baseUrl, this::getUserId);
		locationService     = new CurrentLocation(client, config, httpClient, this::baseUrl, this::getUserId);
		specialService      = new SpecialAttackStatus(client, config, httpClient, this::baseUrl, this::getUserId);
		currentSkillService = new CurrentSkill(client, config, httpClient, this::baseUrl, this::getUserId);
		idleTimer           = new IdleTimer(client, config, httpClient, this::baseUrl, this::getUserId);

		eventBus.register(opponentService);
		eventBus.register(locationService);
		eventBus.register(specialService);
		eventBus.register(currentSkillService);
		eventBus.register(idleTimer);

		boolean online = isOnline();
		opponentService    .init(online);
		locationService    .init(online);
		specialService     .init(online);
		currentSkillService.init(online);
		idleTimer          .init(online);

		/* init push */
		if (online)
		{
			sendStatus("Online");
			if (lastShowHealth)        sendCurrentHealth();
			if (lastShowPrayer)        sendCurrentPrayer();
			if (lastShowEnergy)        sendCurrentEnergy();
			if (lastShowCurrentWorld)  sendCurrentWorld();
			if (lastShowSpecialAttack) sendCurrentSpecial();
		}

		scheduleHeartbeat();

		/* start keyboard HTTP‑server (eksisterende) */
		try
		{
			keyServer = Keyboard.start(config.keyboardBindHost(), config.keyboardPort());
			log.info("Keyboard HTTP‑server startet på {}:{}", config.keyboardBindHost(), config.keyboardPort());
		}
		catch (IOException ex)
		{
			log.error("Kunne ikke starte Keyboard‑server", ex);
		}

		/* ----  NYE DELER  ---- */
		String wsUrl = config.haUrl().replaceFirst("^http", "ws") + "/api/websocket";
		haWsClient = new HaWsClient(wsUrl, config.haToken(), this);
		haWsClient.connect();

		overlay = new HAButtonOverlay(this::isHaButtonOn);
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown() throws Exception
	{
		sendStatus("Offline");
		cancelHeartbeat();

		eventBus.unregister(opponentService);
		eventBus.unregister(locationService);
		eventBus.unregister(specialService);
		eventBus.unregister(currentSkillService);
		eventBus.unregister(idleTimer);

		if (keyServer != null) keyServer.stop(0);
		if (overlay   != null) overlayManager.remove(overlay);

		super.shutDown();
	}

	/* ───────── config changes – uendret logikk ───────── */
	@Subscribe
	public void onConfigChanged(ConfigChanged ev)
	{
		if (!"kp_home_assistant".equals(ev.getGroup()))
			return;

		boolean online = isOnline();
		switch (ev.getKey())
		{
			case "showHealth":
				lastShowHealth = config.showHealth();
				if (online) {
					sendHealthToggle(lastShowHealth);
					if (lastShowHealth) sendCurrentHealth();
				}
				break;
			case "showPrayer":
				lastShowPrayer = config.showPrayer();
				if (online) {
					sendPrayerToggle(lastShowPrayer);
					if (lastShowPrayer) sendCurrentPrayer();
				}
				break;
			case "showEnergy":
				lastShowEnergy = config.showEnergy();
				if (online) {
					sendEnergyToggle(lastShowEnergy);
					if (lastShowEnergy) sendCurrentEnergy();
				}
				break;
			case "showCurrentWorld":
				lastShowCurrentWorld = config.showCurrentWorld();
				if (online) {
					sendWorldToggle(lastShowCurrentWorld);
					if (lastShowCurrentWorld) sendCurrentWorld();
				}
				break;
			case "showCurrentOpponent":
				opponentService.onConfigChanged(ev, online);
				break;
			case "showCurrentLocation":
				locationService.onConfigChanged(ev, online);
				break;
			case "showSpecialAttack":
				lastShowSpecialAttack = config.showSpecialAttack();
				specialService.onConfigChanged(ev, online);
				break;
			case "showCurrentSkill":
				lastShowCurrentSkill = config.showCurrentSkill();
				currentSkillService.onConfigChanged(ev, online);
				break;
			case "showIdleStatus":
				lastShowIdleStatus = config.showIdleStatus();
				idleTimer.onConfigChanged(online);
				break;
			default:
		}
	}

	/* ───────── RuneLite events – uendret logikk ───────── */
	@Subscribe
	public void onGameStateChanged(GameStateChanged evt)
	{
		boolean online = isOnline();
		sendStatus(online ? "Online" : "Offline");

		if (online)
		{
			if (lastShowHealth)        sendCurrentHealth();
			if (lastShowPrayer)        sendCurrentPrayer();
			if (lastShowEnergy)        sendCurrentEnergy();
			if (lastShowCurrentWorld)  sendCurrentWorld();
			if (lastShowSpecialAttack) sendCurrentSpecial();
		}

		opponentService   .onGameStateChanged(evt);
		locationService   .onGameStateChanged(evt);
		specialService    .onStateChange(online);
		currentSkillService.onStateChange(online);
		idleTimer         .onConfigChanged(online);
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned ev)
	{
		if (isOnline())
		{
			sendStatus("Online");
			if (lastShowHealth)        sendCurrentHealth();
			if (lastShowPrayer)        sendCurrentPrayer();
			if (lastShowEnergy)        sendCurrentEnergy();
			if (lastShowCurrentWorld)  sendCurrentWorld();
			if (lastShowSpecialAttack) sendCurrentSpecial();
		}

		opponentService.onPlayerSpawned(ev);
		locationService.onPlayerSpawned(ev);
	}

	@Subscribe
	public void onStatChanged(StatChanged ev)
	{
		if (lastShowHealth && ev.getSkill() == Skill.HITPOINTS)
			sendCurrentHealth();
		if (lastShowPrayer && ev.getSkill() == Skill.PRAYER)
			sendCurrentPrayer();

		currentSkillService.onStatChanged(ev);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (lastShowEnergy        && isOnline()) sendCurrentEnergy();
		if (lastShowSpecialAttack && isOnline()) sendCurrentSpecial();

		opponentService    .onGameTick(tick);
		locationService    .onGameTick(tick);
		specialService     .onGameTick(tick);
		currentSkillService.onGameTick(tick);
	}

	/* ───────── heartbeat ───────── */
	private void scheduleHeartbeat()
	{
		cancelHeartbeat();
		heartbeatTask = executor.scheduleAtFixedRate(() -> {
			boolean online = isOnline();
			sendStatus(online ? "Online" : "Offline");

			if (lastShowHealth)        sendCurrentHealth();
			if (lastShowPrayer)        sendCurrentPrayer();
			if (lastShowEnergy)        sendCurrentEnergy();
			if (lastShowCurrentWorld)  sendCurrentWorld();
			if (lastShowSpecialAttack) sendCurrentSpecial();

			opponentService    .onHeartbeat(online);
			locationService    .onHeartbeat(online);
			specialService     .onHeartbeat(online);
			currentSkillService.onHeartbeat(online);
		}, 0, 10, TimeUnit.SECONDS);
	}

	private void cancelHeartbeat()
	{
		if (heartbeatTask != null)
			heartbeatTask.cancel(false);
		heartbeatTask = null;
	}

	/* ───────── helpers ───────── */
	private boolean isOnline()
	{
		return client.getGameState() == GameState.LOGGED_IN
				&& client.getLocalPlayer() != null;
	}

	private String getUserId()
	{
		Player p = client.getLocalPlayer();
		if (p == null) return "unknown";
		String raw = p.getName().toLowerCase();
		return SANITIZE.matcher(raw).replaceAll("");
	}

	private String baseUrl()
	{
		return config.haUrl().replaceAll("/+$", "");
	}

	private void post(String url, String json)
	{
		RequestBody body = RequestBody.create(JSON, json);
		Request req = new Request.Builder()
				.url(url)
				.addHeader("Authorization", "Bearer " + config.haToken())
				.post(body)
				.build();

		httpClient.newCall(req).enqueue(new Callback()
		{
			@Override public void onFailure(Call c, IOException e) { }
			@Override public void onResponse(Call c, Response r) throws IOException { r.close(); }
		});
	}

	/* ───────── SEND‑metoder (identisk med original) ───────── */

	private void postF(String topic, String fmt, Object... args)
	{
		post(String.format("%s/api/events/kp_runelite_%s_%s",
						baseUrl(), topic, getUserId()),
				String.format(fmt, args));
	}

	private void sendStatus(String status)
	{ postF("update", "{\"status\":\"%s\"}", status); }

	private void sendCurrentHealth()
	{
		int cur = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int max = client.getRealSkillLevel(Skill.HITPOINTS);
		postF("health", "{\"current\":%d,\"max\":%d}", cur, max);
	}

	private void sendHealthToggle(boolean on)
	{ postF("health", "{\"enabled\":%b}", on); }

	private void sendCurrentPrayer()
	{
		int cur = client.getBoostedSkillLevel(Skill.PRAYER);
		int max = client.getRealSkillLevel(Skill.PRAYER);
		postF("prayer", "{\"current\":%d,\"max\":%d}", cur, max);
	}

	private void sendPrayerToggle(boolean on)
	{ postF("prayer", "{\"enabled\":%b}", on); }

	private void sendCurrentEnergy()
	{
		int pct = client.getEnergy() / 100;
		postF("energy", "{\"current\":%d,\"max\":100}", pct);
	}

	private void sendEnergyToggle(boolean on)
	{ postF("energy", "{\"enabled\":%b}", on); }

	private void sendCurrentWorld()
	{
		int world = client.getWorld();
		postF("world", "{\"world\":%d}", world);
	}

	private void sendWorldToggle(boolean on)
	{ postF("world", "{\"enabled\":%b}", on); }

	private void sendCurrentSpecial()
	{
		int pct = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) / 10;
		postF("special", "{\"current\":%d,\"max\":100}", pct);
	}

	private void sendSpecialToggle(boolean on)
	{ postF("special", "{\"enabled\":%b}", on); }
}
