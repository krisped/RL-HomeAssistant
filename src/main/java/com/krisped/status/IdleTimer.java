package com.krisped.status;

import com.krisped.HomeAssistantConfig;
import java.io.IOException;
import java.time.Instant;
import java.util.function.Supplier;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import okhttp3.*;

/**
 * Sender kun én webhook pr. overgang:
 *   { "state":"Idle" }     når sliderterskel passeres
 *   { "state":"Not Idle"}  når spilleren blir aktiv igjen
 */
@Slf4j
public class IdleTimer
{
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private final Client client;
    private final HomeAssistantConfig cfg;
    private final OkHttpClient http;
    private final Supplier<String> baseUrl;
    private final Supplier<String> userId;

    /* interne felter */
    private boolean enabled;
    private boolean idle;
    private Instant idleStart;
    private WorldPoint lastPos;

    /* ───────── ctor ───────── */
    @Inject
    public IdleTimer(
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
        this.enabled = cfg.showIdleStatus();
    }

    /* ───────── init / config ───────── */

    public void init(boolean online)
    {
        idle = false;
        idleStart = null;
        lastPos = null;
        if (online && enabled)
            sendState("Not Idle");
    }

    public void onConfigChanged(boolean online)
    {
        enabled = cfg.showIdleStatus();
        if (online)
        {
            sendToggle(enabled);
            if (enabled)
                sendState(idle ? "Idle" : "Not Idle");
        }
    }

    /* ───────── RuneLite tick ───────── */

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (!enabled || client.getGameState() != GameState.LOGGED_IN) return;

        Player p = client.getLocalPlayer();
        if (p == null) return;

        /* aktiv dersom animasjon eller posisjon endres */
        boolean animActive = p.getAnimation() != -1;
        WorldPoint pos     = p.getWorldLocation();
        boolean moved      = lastPos != null && !lastPos.equals(pos);
        lastPos = pos;

        boolean active = animActive || moved;
        int threshold  = cfg.idleThresholdSeconds();

        if (idle)
        {
            if (active)
            {
                idle = false;
                idleStart = null;
                sendState("Not Idle");
            }
        }
        else     /* ikke idle akkurat nå */
        {
            if (active)
            {
                idleStart = null;           // fortsatt aktiv
            }
            else
            {
                if (idleStart == null)
                    idleStart = Instant.now();

                long diff = Instant.now().getEpochSecond() - idleStart.getEpochSecond();
                if (diff >= threshold)
                {
                    idle = true;
                    sendState("Idle");
                }
            }
        }
    }

    /* ───────── HTTP helpers ───────── */

    private void sendState(String state)
    {
        if (!enabled) return;
        post(
                String.format("%s/api/events/kp_runelite_idle_%s", baseUrl.get(), userId.get()),
                String.format("{\"state\":\"%s\"}", state)
        );
    }

    private void sendToggle(boolean on)
    {
        post(
                String.format("%s/api/events/kp_runelite_idle_%s", baseUrl.get(), userId.get()),
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

        http.newCall(req).enqueue(new Callback()
        {
            @Override public void onFailure(Call c, IOException e) { }
            @Override public void onResponse(Call c, Response r) throws IOException { r.close(); }
        });
    }
}
