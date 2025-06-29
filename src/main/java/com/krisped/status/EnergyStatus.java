package com.krisped.status;

import java.io.IOException;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.client.events.ConfigChanged;    // ← Correct import
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.krisped.HomeAssistantConfig;

public class EnergyStatus
{
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Client client;
    private final HomeAssistantConfig config;
    private final OkHttpClient http;
    private final Supplier<String> baseUrl;
    private final Supplier<String> user;
    private Boolean lastShow;

    public EnergyStatus(Client client,
                        HomeAssistantConfig config,
                        OkHttpClient http,
                        Supplier<String> baseUrl,
                        Supplier<String> user)
    {
        this.client   = client;
        this.config   = config;
        this.http     = http;
        this.baseUrl  = baseUrl;
        this.user     = user;
        this.lastShow = config.showEnergy();
    }

    public void init(boolean online)
    {
        if (online && lastShow)
            sendCurrent();
    }

    public void onConfigChanged(ConfigChanged ev, boolean online)
    {
        if (!"kp_home_assistant".equals(ev.getGroup()) || !"showEnergy".equals(ev.getKey()))
            return;

        lastShow = config.showEnergy();
        if (online)
        {
            sendToggle(lastShow);
            if (lastShow) sendCurrent();
        }
    }

    public void onStateChange(boolean online)
    {
        if (online && lastShow)
            sendCurrent();
    }

    public void onGameTick(GameTick tick)
    {
        if (lastShow && isOnline())
            sendCurrent();
    }

    public void onHeartbeat(boolean online)
    {
        if (online && lastShow)
            sendCurrent();
    }

    private void sendCurrent()
    {
        int raw = client.getEnergy();
        int cur = raw / 100;
        String url  = String.format("%s/api/events/kp_runelite_energy_%s", baseUrl.get(), user.get());
        String body = String.format("{\"current\":%d,\"max\":100}", cur);
        post(url, body);
    }

    private void sendToggle(boolean enabled)
    {
        String url  = String.format("%s/api/events/kp_runelite_energy_%s", baseUrl.get(), user.get());
        String body = String.format("{\"enabled\":%b}", enabled);
        post(url, body);
    }

    private boolean isOnline()
    {
        return client.getGameState() == GameState.LOGGED_IN
                && client.getLocalPlayer() != null;
    }

    private void post(String url, String json)
    {
        RequestBody b = RequestBody.create(JSON, json);
        Request req   = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.haToken())
                .post(b)
                .build();

        http.newCall(req).enqueue(new okhttp3.Callback()
        {
            @Override public void onFailure(okhttp3.Call c, IOException e) { }
            @Override public void onResponse(okhttp3.Call c, Response r) throws IOException { r.close(); }
        });
    }
}
