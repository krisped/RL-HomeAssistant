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

	/* ───────── injects ───────── */
	@Inject private Client client;
	@Inject private HomeAssistantConfig config;
	@Inject private ScheduledExecutorService executor;

	/* ───────── fields ───────── */
	private final OkHttpClient httpClient = new OkHttpClient();

	private ScheduledFuture<?> heartbeatTask;

	private boolean lastShowHealth;
	private boolean lastShowPrayer;
	private boolean lastShowEnergy;
	private boolean lastShowCurrentWorld;
	private boolean lastShowSpecialAttack;
	private boolean lastShowCurrentSkill;                // ★ NEW

	private CurrentOpponent     opponentService;
	private CurrentLocation     locationService;
	private SpecialAttackStatus specialService;
	private CurrentSkill        currentSkillService;     // ★ NEW

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

		/* ─── read toggles ─── */
		lastShowHealth        = config.showHealth();
		lastShowPrayer        = config.showPrayer();
		lastShowEnergy        = config.showEnergy();
		lastShowCurrentWorld  = config.showCurrentWorld();
		lastShowSpecialAttack = config.showSpecialAttack();
		lastShowCurrentSkill  = config.showCurrentSkill();       // ★ NEW

		/* ─── init helpers ─── */
		opponentService = new CurrentOpponent(
				client, config, httpClient,
				this::baseUrl, this::getUserId);
		opponentService.init(isOnline());

		locationService = new CurrentLocation(
				client, config, httpClient,
				this::baseUrl, this::getUserId);
		locationService.init(isOnline());

		specialService = new SpecialAttackStatus(
				client, config, httpClient,
				this::baseUrl, this::getUserId);
		specialService.init(isOnline());

		currentSkillService = new CurrentSkill(            // ★ NEW
				client, config, httpClient,
				this::baseUrl, this::getUserId);
		currentSkillService.init(isOnline());

		/* ─── initial push ─── */
		if (isOnline())
		{
			sendStatus("Online");
			if (lastShowHealth)        sendCurrentHealth();
			if (lastShowPrayer)        sendCurrentPrayer();
			if (lastShowEnergy)        sendCurrentEnergy();
			if (lastShowCurrentWorld)  sendCurrentWorld();
			if (lastShowSpecialAttack) sendCurrentSpecialAttack();
		}

		scheduleHeartbeat();
	}

	@Override
	protected void shutDown() throws Exception
	{
		sendStatus("Offline");
		cancelHeartbeat();
		super.shutDown();
	}

	/* ───────── event handlers ───────── */
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
					sendCurrentWorldToggle(lastShowCurrentWorld);
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
				if (online) {
					sendSpecialAttackToggle(lastShowSpecialAttack);
					if (lastShowSpecialAttack) sendCurrentSpecialAttack();
				}
				specialService.onConfigChanged(ev, online);
				break;

			case "showCurrentSkill":                            // ★ NEW
				lastShowCurrentSkill = config.showCurrentSkill();
				currentSkillService.onConfigChanged(ev, online);
				break;

			default: /* ignore */
		}
	}

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
			if (lastShowSpecialAttack) sendCurrentSpecialAttack();
		}

		opponentService   .onGameStateChanged(evt);
		locationService   .onGameStateChanged(evt);
		specialService    .onStateChange(online);
		currentSkillService.onStateChange(online);           // ★ NEW
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
			if (lastShowSpecialAttack) sendCurrentSpecialAttack();
		}

		opponentService   .onPlayerSpawned(ev);
		locationService   .onPlayerSpawned(ev);
	}

	@Subscribe
	public void onStatChanged(StatChanged ev)
	{
		if (lastShowHealth  && ev.getSkill() == Skill.HITPOINTS)
			sendCurrentHealth();
		if (lastShowPrayer  && ev.getSkill() == Skill.PRAYER)
			sendCurrentPrayer();

		currentSkillService.onStatChanged(ev);               // ★ NEW
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (lastShowEnergy        && isOnline()) sendCurrentEnergy();
		if (lastShowSpecialAttack && isOnline()) sendCurrentSpecialAttack();

		opponentService    .onGameTick(tick);
		locationService    .onGameTick(tick);
		specialService     .onGameTick(tick);
		currentSkillService.onGameTick(tick);                // ★ NEW
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
			if (lastShowSpecialAttack) sendCurrentSpecialAttack();

			opponentService    .onHeartbeat(online);
			locationService    .onHeartbeat(online);
			specialService     .onHeartbeat(online);
			currentSkillService.onHeartbeat(online);          // ★ NEW
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

		httpClient.newCall(req).enqueue(new okhttp3.Callback()
		{
			@Override public void onFailure(okhttp3.Call c, IOException e) { }
			@Override public void onResponse(okhttp3.Call c, Response r) throws IOException { r.close(); }
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
		String id  = getUserId();
		int cur    = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int max    = client.getRealSkillLevel(Skill.HITPOINTS);
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
		String id  = getUserId();
		int cur    = client.getBoostedSkillLevel(Skill.PRAYER);
		int max    = client.getRealSkillLevel(Skill.PRAYER);
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
		int pct   = client.getEnergy() / 100;
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
		String id    = getUserId();
		int world    = client.getWorld();
		post(String.format("%s/api/events/kp_runelite_world_%s", baseUrl(), id),
				String.format("{\"world\":%d}", world));
	}

	private void sendCurrentWorldToggle(boolean on)
	{
		String id = getUserId();
		post(String.format("%s/api/events/kp_runelite_world_%s", baseUrl(), id),
				String.format("{\"enabled\":%b}", on));
	}

	private void sendCurrentSpecialAttack()
	{
		String id  = getUserId();
		int pct    = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) / 10;
		post(String.format("%s/api/events/kp_runelite_special_%s", baseUrl(), id),
				String.format("{\"current\":%d,\"max\":100}", pct));
	}

	private void sendSpecialAttackToggle(boolean on)
	{
		String id = getUserId();
		post(String.format("%s/api/events/kp_runelite_special_%s", baseUrl(), id),
				String.format("{\"enabled\":%b}", on));
	}
}
