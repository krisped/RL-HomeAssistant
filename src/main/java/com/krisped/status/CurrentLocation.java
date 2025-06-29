package com.krisped.status;

import com.krisped.HomeAssistantConfig;
import java.io.IOException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.client.events.ConfigChanged;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
public class CurrentLocation
{
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private final Client client;
    private final HomeAssistantConfig cfg;
    private final OkHttpClient http;
    private final Supplier<String> baseUrl;
    private final Supplier<String> userId;
    private boolean lastShow;

    public CurrentLocation(
            Client client,
            HomeAssistantConfig cfg,
            OkHttpClient http,
            Supplier<String> baseUrl,
            Supplier<String> userId)
    {
        this.client   = client;
        this.cfg      = cfg;
        this.http     = http;
        this.baseUrl  = baseUrl;
        this.userId   = userId;
        this.lastShow = cfg.showCurrentLocation();
    }

    /* ─────────── Lifecycle ─────────── */

    public void init(boolean online)
    {
        if (online && lastShow)
            sendCurrentLocation();
    }

    public void onConfigChanged(ConfigChanged ev, boolean online)
    {
        if (!"kp_home_assistant".equals(ev.getGroup())
                || !"showCurrentLocation".equals(ev.getKey()))
            return;

        lastShow = cfg.showCurrentLocation();
        if (online)
        {
            sendToggle(lastShow);
            if (lastShow) sendCurrentLocation();
        }
    }

    public void onGameStateChanged(GameStateChanged ev)
    {
        if (lastShow && client.getGameState() == GameState.LOGGED_IN)
            sendCurrentLocation();
    }

    public void onPlayerSpawned(PlayerSpawned ev)
    {
        if (lastShow)
            sendCurrentLocation();
    }

    public void onGameTick(GameTick tick)
    {
        if (lastShow && client.getGameState() == GameState.LOGGED_IN)
            sendCurrentLocation();
    }

    public void onHeartbeat(boolean online)
    {
        if (online && lastShow)
            sendCurrentLocation();
    }

    /* ─────────── Sending ─────────── */

    private void sendCurrentLocation()
    {
        Player p = client.getLocalPlayer();
        if (p == null) return;

        WorldPoint wp = p.getWorldLocation();
        String loc = locationName(wp);

        post(
                String.format("%s/api/events/kp_runelite_location_%s",
                        baseUrl.get(), userId.get()),
                String.format("{\"location\":\"%s\"}", loc)
        );
    }

    private void sendToggle(boolean enabled)
    {
        post(
                String.format("%s/api/events/kp_runelite_location_%s",
                        baseUrl.get(), userId.get()),
                String.format("{\"enabled\":%b}", enabled)
        );
    }

    /* ─────────── Helpers ─────────── */

    /**
     * Grov mapping: legg til flere region-ID-er etter behov.
     * Region-tallene er de samme som i RuneLite‐kildene.
     */

    /**
     * Returnerer et lesbart stedsnavn for gitt WorldPoint.
     * Alfabetisk sortert, flere case-linjer kan peke på samme navn.
     * Legg gjerne til/endre ID-er senere – Dev Tools → World point viser regionID.
     */
    private static String locationName(WorldPoint wp)
    {
        switch (wp.getRegionID())
        {
            /* ───────── A ───────── */
            case 13105:
            case 13106:
                return "Al Kharid";

            case 10291:
            case 10547:
                return "Ardougne East";

            case 10035:
                return "Ardougne West";

            /* ───────── B ───────── */
            case 10039:
                return "Barbarian Outpost";

            case 12341:
                return "Barbarian Village";

            case 11573:
            case 11574:
                return "Burthorpe / Taverley";

            /* ───────── C ───────── */
            case 11062:
                return "Camelot";

            case 13878:
            case 12747:
                return "Canifis";

            /* ───────── D ───────── */
            case 13365:
                return "Digsite";

            case 12340:
                return "Draynor Manor";

            case 12338:
                return "Draynor Village";

            /* ───────── E ───────── */
            case 12342:
            case 11835:
                return "Edgeville";

            case 11316:
                return "Entrana";

            /* ───────── F ───────── */
            case 11827:
            case 11828:
                return "Falador";

            case 12084:
                return "Falador East";

            case 13362:
                return "Ferox Enclave";

            /* ───────── G ───────── */
            case 12598:
                return "Grand Exchange";

            /* ───────── H ───────── */
            case 14642:
                return "Hosidius";

            /* ───────── K ───────── */
            case 11057:
            case 11059:
            case 11310:
                return "Karamja";

            case 11423:
                return "Keldagrim";

            /* ───────── L ───────── */
            case 12850:
                return "Lumbridge";

            case 12593:
            case 12849:
            case 12851:
                return "Lumbridge Swamp";

            case 12345:
            case 12589:
                return "Low Wilderness";

            /* ───────── P ───────── */
            case 14997:
                return "Piscarilius";

            case 14646:
                return "Port Phasmatys";

            case 12081:
            case 12082:
                return "Port Sarim";

            case 13151:
            case 13152:
                return "Prifddinas";

            /* ───────── R ───────── */
            case 10553:
            case 10554:
            case 10555:
            case 11050:
                return "Rellekka";

            /* ───────── S ───────── */
            case 10806:
                return "Seers' Village";

            case 14745:
                return "Shayzien";

            case 6189:
            case 6445:
            case 6701:
                return "South Varlamore";

            /* ───────── T ───────── */
            case 10033:
                return "Tree Gnome Village";

            /* ───────── V ───────── */
            case 12853:
            case 12854:
                return "Varrock";

            case 12597:
                return "Varrock West";

            /* ───────── Y ───────── */
            case 10057:
                return "Yanille";

            /* ───────── Default ───────── */
            default:
                return String.format("Region %d", wp.getRegionID());
        }
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
