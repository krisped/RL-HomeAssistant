package com.krisped.status;

import com.krisped.HomeAssistantConfig;
import java.io.IOException;
import java.util.function.Supplier;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.GameTick;
import net.runelite.client.events.ConfigChanged;
import okhttp3.*;

@Slf4j
public class SpecialAttackStatus
{
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private final Client client;
    private final HomeAssistantConfig cfg;
    private final OkHttpClient http;
    private final Supplier<String> baseUrl;
    private final Supplier<String> user;
    private boolean lastShow;

    @Inject
    public SpecialAttackStatus(
            Client client,
            HomeAssistantConfig cfg,
            OkHttpClient http,
            Supplier<String> baseUrl,
            Supplier<String> user)
    {
        this.client   = client;
        this.cfg      = cfg;
        this.http     = http;
        this.baseUrl  = baseUrl;
        this.user     = user;
        this.lastShow = cfg.showSpecialAttack();
    }

    /* ─────────── lifecycle ─────────── */

    public void init(boolean online)
    {
        if (online && lastShow)
            sendCurrent();
    }

    public void onConfigChanged(ConfigChanged ev, boolean online)
    {
        if (!"kp_home_assistant".equals(ev.getGroup())
                || !"showSpecialAttack".equals(ev.getKey()))
            return;

        lastShow = cfg.showSpecialAttack();
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

    /* ─────────── sending ─────────── */

    private void sendCurrent()
    {
        int raw = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT); // 0-1000
        int pct = raw / 10;
        String url  = String.format("%s/api/events/kp_runelite_special_%s",
                baseUrl.get(), user.get());
        String body = String.format("{\"current\":%d,\"max\":100}", pct);
        post(url, body);
    }

    private void sendToggle(boolean enabled)
    {
        String url  = String.format("%s/api/events/kp_runelite_special_%s",
                baseUrl.get(), user.get());
        String body = String.format("{\"enabled\":%b}", enabled);
        post(url, body);
    }

    /* ─────────── helpers ─────────── */

    private boolean isOnline()
    {
        return client.getGameState() == GameState.LOGGED_IN
                && client.getLocalPlayer() != null;
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
