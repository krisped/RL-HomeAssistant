package com.krisped.status;

import java.io.IOException;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.events.ConfigChanged;    // ← Correct import
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.krisped.HomeAssistantConfig;

public class PrayerStatus
{
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Client client;
    private final HomeAssistantConfig config;
    private final OkHttpClient http;
    private final Supplier<String> baseUrl;
    private final Supplier<String> user;
    private Boolean lastShow;

    public PrayerStatus(Client client,
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
        this.lastShow = config.showPrayer();
    }

    public void init(boolean online)
    {
        if (online && lastShow)
            sendCurrent();
    }

    public void onConfigChanged(ConfigChanged ev, boolean online)
    {
        if (!"kp_home_assistant".equals(ev.getGroup()) || !"showPrayer".equals(ev.getKey()))
            return;

        lastShow = config.showPrayer();
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

    public void onStatChanged(StatChanged ev)
    {
        if (lastShow && ev.getSkill() == Skill.PRAYER)
            sendCurrent();
    }

    public void onHeartbeat(boolean online)
    {
        if (online && lastShow)
            sendCurrent();
    }

    private void sendCurrent()
    {
        int cur = client.getBoostedSkillLevel(Skill.PRAYER);
        int max = client.getRealSkillLevel(Skill.PRAYER);
        String url  = String.format("%s/api/events/kp_runelite_prayer_%s", baseUrl.get(), user.get());
        String body = String.format("{\"current\":%d,\"max\":%d}", cur, max);
        post(url, body);
    }

    private void sendToggle(boolean enabled)
    {
        String url  = String.format("%s/api/events/kp_runelite_prayer_%s", baseUrl.get(), user.get());
        String body = String.format("{\"enabled\":%b}", enabled);
        post(url, body);
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
