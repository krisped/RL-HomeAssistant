package com.krisped.status;

import com.krisped.HomeAssistantConfig;
import java.io.IOException;
import java.util.function.Supplier;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.Subscribe;
import okhttp3.*;

@Slf4j
public class CurrentWorld
{
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private final Client client;
    private final HomeAssistantConfig cfg;
    private final OkHttpClient http;
    private final Supplier<String> baseUrl;
    private final Supplier<String> userId;

    @Inject
    public CurrentWorld(
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
    }

    /* ───────── lifecycle ───────── */
    public void init(boolean online) { if (online) send(); }

    @Subscribe public void onGameStateChanged(GameStateChanged e)
    { if (client.getGameState()==GameState.LOGGED_IN) send(); }

    @Subscribe public void onPlayerSpawned(PlayerSpawned e) { send(); }
    @Subscribe public void onGameTick(GameTick e)
    { if (client.getGameState()==GameState.LOGGED_IN) send(); }

    private void send()
    {
        if (client.getLocalPlayer()==null) return;

        int world = client.getWorld();
        String json = String.format("{\"world\":%d}", world);

        post(String.format("%s/api/events/kp_runelite_world_%s", baseUrl.get(), userId.get()),
                json);
    }

    private void post(String url, String json)
    {
        RequestBody body = RequestBody.create(JSON, json);
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer "+cfg.haToken())
                .post(body)
                .build();

        http.newCall(req).enqueue(new okhttp3.Callback()
        {
            @Override public void onFailure(okhttp3.Call c, IOException e) { }
            @Override public void onResponse(okhttp3.Call c, Response r) throws IOException { r.close(); }
        });
    }
}
