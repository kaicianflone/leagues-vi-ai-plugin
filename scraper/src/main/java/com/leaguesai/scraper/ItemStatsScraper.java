package com.leaguesai.scraper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches bulk equipment stats from the OSRS Wiki Cargo API.
 *
 * <p>This is a single-table bulk query — one HTTP call retrieves many items,
 * not one-per-item. Pagination is handled automatically via
 * {@link #fetchAll()}.
 *
 * <p>NOTE: Do not call this from WikiScraper's main task loop. The bulk stats
 * fetch is expensive and is intended to be run separately via a dedicated
 * 'scrape-items' Gradle task (TODO: wire that up).
 *
 * <p>No live HTTP tests are provided for this class; the Cargo API is
 * integration-only. Use a mock OkHttpClient in unit tests if needed.
 */
public class ItemStatsScraper {

    private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
    private static final String USER_AGENT = "leagues-vi-ai-scraper/1.0 (github.com/leagues-vi-ai)";

    private static final String CARGO_FIELDS =
        "_pageName,slot," +
        "combat_attack_stab,combat_attack_slash,combat_attack_crush," +
        "combat_attack_magic,combat_attack_ranged," +
        "combat_defence_stab,combat_defence_slash,combat_defence_crush," +
        "combat_defence_magic,combat_defence_ranged," +
        "combat_melee_strength,combat_magic_damage,combat_ranged_strength," +
        "combat_prayer,weight";

    private final OkHttpClient httpClient;

    public ItemStatsScraper(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Fetches up to {@code limit} equipment rows from the Cargo API starting
     * at {@code offset}.
     *
     * @param offset zero-based row offset for pagination
     * @param limit  maximum rows to return per call (max 500 per wiki API)
     * @return parsed rows; may be empty if no results at this offset
     * @throws IOException on HTTP or JSON parsing errors
     */
    public List<ItemStatsRow> fetchPage(int offset, int limit) throws IOException {
        HttpUrl url = HttpUrl.parse(WIKI_API).newBuilder()
            .addQueryParameter("action", "cargoquery")
            .addQueryParameter("tables", "Equipment")
            .addQueryParameter("fields", CARGO_FIELDS)
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", String.valueOf(limit))
            .addQueryParameter("offset", String.valueOf(offset))
            .build();

        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Cargo API returned HTTP " + response.code());
            }
            String body = response.body().string();
            return parseCargoResponse(body);
        }
    }

    /**
     * Fetches all pages until no more results. Stops when a page returns fewer
     * rows than the requested limit (indicating the last page).
     *
     * @return all equipment rows from the wiki
     * @throws IOException on HTTP or JSON parsing errors
     */
    public List<ItemStatsRow> fetchAll() throws IOException {
        List<ItemStatsRow> all = new ArrayList<>();
        int offset = 0;
        int limit = 500;

        while (true) {
            List<ItemStatsRow> page = fetchPage(offset, limit);
            all.addAll(page);
            if (page.size() < limit) {
                break;
            }
            offset += page.size();
        }

        return all;
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private List<ItemStatsRow> parseCargoResponse(String json) {
        List<ItemStatsRow> rows = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("cargoquery")) {
            return rows;
        }
        JsonArray cargoquery = root.getAsJsonArray("cargoquery");
        for (JsonElement element : cargoquery) {
            JsonObject title = element.getAsJsonObject().getAsJsonObject("title");
            if (title == null) continue;

            ItemStatsRow row = new ItemStatsRow();
            row.pageName        = getString(title, "_pageName");
            row.slot            = getString(title, "slot");
            row.attackStab      = getInt(title, "combat attack stab");
            row.attackSlash     = getInt(title, "combat attack slash");
            row.attackCrush     = getInt(title, "combat attack crush");
            row.attackMagic     = getInt(title, "combat attack magic");
            row.attackRanged    = getInt(title, "combat attack ranged");
            row.defenceStab     = getInt(title, "combat defence stab");
            row.defenceSlash    = getInt(title, "combat defence slash");
            row.defenceCrush    = getInt(title, "combat defence crush");
            row.defenceMagic    = getInt(title, "combat defence magic");
            row.defenceRanged   = getInt(title, "combat defence ranged");
            row.meleeStrength   = getInt(title, "combat melee strength");
            row.magicDamage     = getInt(title, "combat magic damage");
            row.rangedStrength  = getInt(title, "combat ranged strength");
            row.prayerBonus     = getInt(title, "combat prayer");
            row.weight          = getDouble(title, "weight");
            rows.add(row);
        }
        return rows;
    }

    private String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        String s = el.getAsString().trim();
        return s.isEmpty() ? null : s;
    }

    private int getInt(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return 0;
        String s = el.getAsString().trim();
        if (s.isEmpty()) return 0;
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double getDouble(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return 0.0;
        String s = el.getAsString().trim();
        if (s.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // -------------------------------------------------------------------------

    /** One row of equipment stats from the Wiki Cargo API. */
    public static class ItemStatsRow {
        public String pageName;
        public String slot;
        public int attackStab, attackSlash, attackCrush, attackMagic, attackRanged;
        public int defenceStab, defenceSlash, defenceCrush, defenceMagic, defenceRanged;
        public int meleeStrength, magicDamage, rangedStrength, prayerBonus;
        public double weight;
    }
}
