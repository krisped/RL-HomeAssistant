package com.krisped.status;

import com.krisped.HomeAssistantConfig;
import java.io.IOException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import okhttp3.*;

@Slf4j
public class CurrentSkill
{
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");
    private static final long IDLE_TIMEOUT_MS = 120_000;   // 2 min
    private static final long START_DELAY_MS  =   5_000;   // 5 s

    private final Client client;
    private final HomeAssistantConfig cfg;
    private final OkHttpClient http;
    private final Supplier<String> baseUrl;
    private final Supplier<String> userId;

    private boolean lastShow;
    private boolean snapshotTaken = false;
    private long    allowFromMs   = 0;

    private final Map<Skill,Integer> xpMap = new EnumMap<>(Skill.class);
    private String rememberedSkill = null;
    private long   lastSeenMillis  = 0;

    public CurrentSkill(Client client, HomeAssistantConfig cfg, OkHttpClient http,
                        Supplier<String> baseUrl, Supplier<String> userId)
    {
        this.client   = client;
        this.cfg      = cfg;
        this.http     = http;
        this.baseUrl  = baseUrl;
        this.userId   = userId;
        this.lastShow = cfg.showCurrentSkill();
    }

    /* ───────── helpers ───────── */
    private void resetSnapshot()
    {
        snapshotTaken   = false;
        rememberedSkill = null;
        lastSeenMillis  = 0;
        allowFromMs     = Instant.now().toEpochMilli() + START_DELAY_MS;
    }

    /* ───────── lifecycle ───────── */
    public void init(boolean online)
    {
        if (online && lastShow) sendSkill("None");
        resetSnapshot();
    }

    /** Kalles fra plugin når online/offline endres. */
    public void onStateChange(boolean online)
    {
        if (online && lastShow) sendSkill("None");
        resetSnapshot();
    }

    /** Kalles hvert 10. s fra plugin – holder grensesnittet komplett. */
    public void onHeartbeat(boolean online) { /* ingen periodisk logikk nødvendig */ }

    public void onConfigChanged(ConfigChanged ev, boolean online)
    {
        if (!"kp_home_assistant".equals(ev.getGroup())
                || !"showCurrentSkill".equals(ev.getKey())) return;

        lastShow = cfg.showCurrentSkill();
        if (online)
        {
            sendToggle(lastShow);
            sendSkill(lastShow ? "None" : "Disabled");
            resetSnapshot();
        }
    }

    /* ───────── reset på hop/login ───────── */
    @Subscribe public void onGameStateChanged(GameStateChanged e)
    { if (e.getGameState()==net.runelite.api.GameState.LOGGED_IN) resetSnapshot(); }

    @Subscribe public void onPlayerSpawned(PlayerSpawned e) { resetSnapshot(); }

    /* ───────── StatChanged ───────── */
    @Subscribe public void onStatChanged(StatChanged ev)
    {
        if (!lastShow) return;
        if (Instant.now().toEpochMilli() < allowFromMs) return;

        Skill skill = ev.getSkill();
        if (skill == Skill.HITPOINTS) return;          // hopp over HP-regen

        int newXp = ev.getXp();

        if (!snapshotTaken)                            // første reelle dropp
        {
            for (Skill s : Skill.values())
                xpMap.put(s, client.getSkillExperience(s));
            snapshotTaken = true;
        }

        int prev = xpMap.getOrDefault(skill, newXp);
        if (newXp <= prev) return;                     // ingen økning

        xpMap.put(skill, newXp);
        rememberedSkill = skill.getName();
        lastSeenMillis  = Instant.now().toEpochMilli();
        sendSkill(rememberedSkill);
    }

    @Subscribe public void onGameTick(GameTick t)
    {
        if (!lastShow) return;

        if (rememberedSkill != null
                && Instant.now().toEpochMilli() - lastSeenMillis >= IDLE_TIMEOUT_MS)
        {
            rememberedSkill = null;
            sendSkill("None");
        }
    }

    /* ───────── HTTP ───────── */
    private void sendSkill(String s)
    {
        post(String.format("%s/api/events/kp_runelite_skill_%s", baseUrl.get(), userId.get()),
                String.format("{\"skill\":\"%s\"}", s));
    }
    private void sendToggle(boolean en)
    {
        post(String.format("%s/api/events/kp_runelite_skill_%s", baseUrl.get(), userId.get()),
                String.format("{\"enabled\":%b}", en));
    }
    private void post(String url, String json)
    {
        RequestBody b = RequestBody.create(JSON, json);
        Request req  = new Request.Builder()
                .url(url)
                .addHeader("Authorization","Bearer "+cfg.haToken())
                .post(b)
                .build();

        http.newCall(req).enqueue(new okhttp3.Callback()
        {
            @Override public void onFailure(okhttp3.Call c, IOException e) { }
            @Override public void onResponse(okhttp3.Call c, Response r) throws IOException { r.close(); }
        });
    }
}
