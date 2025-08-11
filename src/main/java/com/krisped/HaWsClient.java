package com.krisped;

import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

/**
 * Lytter på HA‑WebSocket og oppdaterer overlay‑state.
 */
@Slf4j
public class HaWsClient extends WebSocketListener
{
    private final String wsUrl;
    private final String token;
    private final HomeAssistantPlugin plugin;
    private WebSocket ws;
    private final Gson gson = new Gson();

    public HaWsClient(String wsUrl, String token, HomeAssistantPlugin plugin)
    {
        this.wsUrl  = wsUrl;
        this.token  = token;
        this.plugin = plugin;
    }

    public void connect()
    {
        OkHttpClient client = new OkHttpClient();
        Request req = new Request.Builder().url(wsUrl).build();
        client.newWebSocket(req, this);
    }

    /* ───────── WebSocketListener ───────── */

    @Override
    public void onOpen(WebSocket webSocket, Response response)
    {
        ws = webSocket;
        JsonObject auth = new JsonObject();
        auth.addProperty("type", "auth");
        auth.addProperty("access_token", token);
        ws.send(gson.toJson(auth));
        log.info("HA WS connected – auth sent");
    }

    @Override
    public void onMessage(WebSocket webSocket, String text)
    {
        JsonObject msg  = JsonParser.parseString(text).getAsJsonObject();
        String      typ = msg.get("type").getAsString();

        if ("auth_ok".equals(typ))
        {
            JsonObject sub = new JsonObject();
            sub.addProperty("id", 1);
            sub.addProperty("type", "subscribe_events");
            sub.addProperty("event_type", "kp_runelite_button_check");
            ws.send(gson.toJson(sub));
            log.info("Subscribed to kp_runelite_button_check");
        }
        else if ("event".equals(typ))
        {
            JsonObject data = msg.getAsJsonObject("event").getAsJsonObject("data");
            boolean on = "ON".equalsIgnoreCase(data.get("state").getAsString());
            plugin.setHaButtonState(on);
        }
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason)
    {
        log.info("HA WS closing: {} / {}", code, reason);
        webSocket.close(1000, null);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason)
    {
        log.info("HA WS closed: {} / {}", code, reason);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response)
    {
        log.error("HA WS failure", t);
    }
}
