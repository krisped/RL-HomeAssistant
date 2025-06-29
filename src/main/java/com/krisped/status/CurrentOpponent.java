package com.krisped.status;

import com.krisped.HomeAssistantConfig;
import java.io.IOException;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.events.ConfigChanged;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
public class CurrentOpponent
{
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");
    private static final Pattern SANITIZE =
            Pattern.compile("[^a-z0-9]");

    private static final long IDLE_TIMEOUT_MS = 5_000;   // 5 sek før «None»

    /* ───────── deps ───────── */
    private final Client client;
    private final HomeAssistantConfig cfg;
    private final OkHttpClient http;
    private final Supplier<String> baseUrl;
    private final Supplier<String> userId;

    /* ───────── state ───────── */
    private boolean lastShow;
    private String  rememberedOpponent = null;
    private long    lastSeenMillis     = 0;

    public CurrentOpponent(
            Client client,
            HomeAssistantConfig cfg,
            OkHttpClient http,
            Supplier<String> baseUrl,
            Supplier<String> userId)
    {
        this.client  = client;
        this.cfg     = cfg;
        this.http    = http;
        this.baseUrl = baseUrl;
        this.userId  = userId;
        this.lastShow = cfg.showCurrentOpponent();
    }

    /* ───────── init ───────── */
    public void init(boolean online)
    {
        if (online && lastShow)
            tickAndMaybeSend();
    }

    /* ───────── config endret ───────── */
    public void onConfigChanged(ConfigChanged ev, boolean online)
    {
        if (!"kp_home_assistant".equals(ev.getGroup())
                || !"showCurrentOpponent".equals(ev.getKey()))
            return;

        lastShow = cfg.showCurrentOpponent();
        if (online)
        {
            sendToggle(lastShow);
            if (lastShow) tickAndMaybeSend();
        }
    }

    /* ───────── events ───────── */
    public void onGameStateChanged(GameStateChanged ev) { if (lastShow) tickAndMaybeSend(); }
    public void onPlayerSpawned   (PlayerSpawned     ev) { if (lastShow) tickAndMaybeSend(); }
    public void onGameTick        (GameTick          t)  { if (lastShow) tickAndMaybeSend(); }
    public void onHeartbeat       (boolean online)       { if (lastShow && online) tickAndMaybeSend(); }

    /* ───────── hovedlogikk ───────── */
    private void tickAndMaybeSend()
    {
        String now = null;
        Actor target = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getInteracting()
                : null;
        if (target != null && target.getName() != null)
            now = target.getName();

        long nowMs = Instant.now().toEpochMilli();
        boolean changed = false;

        if (now != null)
        {
            if (!now.equals(rememberedOpponent))
            {
                rememberedOpponent = now;
                changed = true;
            }
            lastSeenMillis = nowMs;
        }
        else if (rememberedOpponent != null && nowMs - lastSeenMillis >= IDLE_TIMEOUT_MS)
        {
            rememberedOpponent = null;
            changed = true;
        }

        if (changed)
            sendOpponent(rememberedOpponent == null ? "None" : rememberedOpponent);
    }

    /* ───────── sending ───────── */
    private void sendOpponent(String opp)
    {
        post(
                String.format("%s/api/events/kp_runelite_opponent_%s",
                        baseUrl.get(), userId.get()),
                String.format("{\"opponent\":\"%s\"}", opp)
        );
    }

    private void sendToggle(boolean on)
    {
        post(
                String.format("%s/api/events/kp_runelite_opponent_%s",
                        baseUrl.get(), userId.get()),
                String.format("{\"enabled\":%b}", on)
        );
    }

    private void post(String url, String json)
    {
        RequestBody body = RequestBody.create(JSON, json);
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + cfg.haToken())
                .post(body)
                .build();

        http.newCall(req).enqueue(new okhttp3.Callback()
        {
            @Override public void onFailure(okhttp3.Call c, IOException e) { /* ignore */ }
            @Override public void onResponse(okhttp3.Call c, Response r) throws IOException { r.close(); }
        });
    }
}
