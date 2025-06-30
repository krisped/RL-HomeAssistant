package com.krisped;

import com.google.inject.Provides;
import com.krisped.status.*;
import java.io.IOException;
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
import okhttp3.*;

/**
 * KP RuneLite → Home Assistant bridge.
 */
@Slf4j
@PluginDescriptor(
		name = "KP Home Assistant",
		enabledByDefault = true
)
public class HomeAssistantPlugin extends Plugin
{
	/* ───────── constants ───────── */
	private static final MediaType JSON =
			MediaType.get("application/json; charset=utf-8");
	private static final Pattern SANITIZE =
			Pattern.compile("[^a-z0-9]");

	/* ───────── injected ───────── */
	@Inject private Client client;
	@Inject private HomeAssistantConfig config;
	@Inject private ScheduledExecutorService executor;
	@Inject private EventBus eventBus;

	/* ───────── fields ───────── */
	private final OkHttpClient httpClient = new OkHttpClient();
	private ScheduledFuture<?> heartbeatTask;

	private boolean lastShowHealth;
	private boolean lastShowPrayer;
	private boolean lastShowEnergy;
	private boolean lastShowCurrentWorld;
	private boolean lastShowSpecialAttack;
	private boolean lastShowCurrentSkill;
	private boolean lastShowIdleStatus;

	private CurrentOpponent     opponentService;
	private CurrentLocation     locationService;
	private SpecialAttackStatus specialService;
	private CurrentSkill        currentSkillService;
	private IdleTimer           idleTimer;

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

		/* read toggles */
		lastShowHealth        = config.showHealth();
		lastShowPrayer        = config.showPrayer();
		lastShowEnergy        = config.showEnergy();
		lastShowCurrentWorld  = config.showCurrentWorld();
		lastShowSpecialAttack = config.showSpecialAttack();
		lastShowCurrentSkill  = config.showCurrentSkill();
		lastShowIdleStatus    = config.showIdleStatus();

		/* init helper services */
		opponentService    = new CurrentOpponent(client, config, httpClient, this::baseUrl, this::getUserId);
		locationService    = new CurrentLocation(client, config, httpClient, this::baseUrl, this::getUserId);
		specialService     = new SpecialAttackStatus(client, config, httpClient, this::baseUrl, this::getUserId);
		currentSkillService= new CurrentSkill(client, config, httpClient, this::baseUrl, this::getUserId);
		idleTimer          = new IdleTimer(client, config, httpClient, this::baseUrl, this::getUserId);

		/* register for events */
		eventBus.register(opponentService);
		eventBus.register(locationService);
		eventBus.register(specialService);
		eventBus.register(currentSkillService);
		eventBus.register(idleTimer);

		boolean online = isOnline();
		opponentService   .init(online);
		locationService   .init(online);
		specialService    .init(online);
		currentSkillService.init(online);
		idleTimer         .init(online);

		/* initial push */
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

		super.shutDown();
	}

	/* ───────── config changes ───────── */
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

			default: /* ignore */
		}
	}

	/* ───────── RuneLite events ───────── */
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

		opponentService .onPlayerSpawned(ev);
		locationService .onPlayerSpawned(ev);
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

	/* ───────── SEND-metoder ───────── */

	private void sendStatus(String status)
	{
		String id = getUserId();
		post(String.format("%s/api/events/kp_runelite_update_%s", baseUrl(), id),
				String.format("{\"status\":\"%s\"}", status));
	}

	private void sendCurrentHealth()
	{
		String id = getUserId();
		int cur = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int max = client.getRealSkillLevel(Skill.HITPOINTS);
		post(String.format("%s/api/events/kp_runelite_health_%s", baseUrl(), id),
				String.format("{\"current\":%d,\"max\":%d}", cur, max));
	}

	private void sendHealthToggle(boolean on)
	{
		String id = getUserId();
		post(String.format("%s/api/events/kp_runelite_health_%s", baseUrl(), id),
				String.format("{\"enabled\":%b}", on));
	}

	private void sendCurrentPrayer()
	{
		String id = getUserId();
		int cur = client.getBoostedSkillLevel(Skill.PRAYER);
		int max = client.getRealSkillLevel(Skill.PRAYER);
		post(String.format("%s/api/events/kp_runelite_prayer_%s", baseUrl(), id),
				String.format("{\"current\":%d,\"max\":%d}", cur, max));
	}

	private void sendPrayerToggle(boolean on)
	{
		String id = getUserId();
		post(String.format("%s/api/events/kp_runelite_prayer_%s", baseUrl(), id),
				String.format("{\"enabled\":%b}", on));
	}

	private void sendCurrentEnergy()
	{
		String id = getUserId();
		int pct = client.getEnergy() / 100;
		post(String.format("%s/api/events/kp_runelite_energy_%s", baseUrl(), id),
				String.format("{\"current\":%d,\"max\":100}", pct));
	}

	private void sendEnergyToggle(boolean on)
	{
		String id = getUserId();
		post(String.format("%s/api/events/kp_runelite_energy_%s", baseUrl(), id),
				String.format("{\"enabled\":%b}", on));
	}

	private void sendCurrentWorld()
	{
		String id = getUserId();
		int world = client.getWorld();
		post(String.format("%s/api/events/kp_runelite_world_%s", baseUrl(), id),
				String.format("{\"world\":%d}", world));
	}

	private void sendWorldToggle(boolean on)
	{
		String id = getUserId();
		post(String.format("%s/api/events/kp_runelite_world_%s", baseUrl(), id),
				String.format("{\"enabled\":%b}", on));
	}

	private void sendCurrentSpecial()
	{
		String id = getUserId();
		int pct = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) / 10;
		post(String.format("%s/api/events/kp_runelite_special_%s", baseUrl(), id),
				String.format("{\"current\":%d,\"max\":100}", pct));
	}

	private void sendSpecialToggle(boolean on)
	{
		String id = getUserId();
		post(String.format("%s/api/events/kp_runelite_special_%s", baseUrl(), id),
				String.format("{\"enabled\":%b}", on));
	}
}
