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
import java.util.Collections;
import java.util.List;

/**
 * Fetches equipment stats from the OSRS Wiki by:
 * <ol>
 *   <li>Walking {@code Category:Equipment} to collect page titles
 *       (paginated via {@code cmcontinue}).</li>
 *   <li>Batch-fetching wikitext for up to {@value #BATCH_SIZE} pages at once
 *       via {@code action=query&prop=revisions&rvprop=content}.</li>
 *   <li>Parsing {@code {{Infobox Bonuses}}} from each page's wikitext.</li>
 * </ol>
 *
 * <p>This replaces the earlier Cargo-based approach — the OSRS wiki does not
 * have the Cargo MediaWiki extension installed.
 *
 * <p>No live HTTP tests are provided for this class; it is integration-only.
 */
public class ItemStatsScraper {

    private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
    private static final String USER_AGENT =
            "leagues-vi-ai-scraper/1.0 (github.com/kaicianflone/leagues-vi-ai-plugin)";

    /** Pages per category-members query (wiki max is 500). */
    private static final int CAT_LIMIT = 500;
    /** Pages per batch wikitext fetch (wiki max is 50 for revisions). */
    private static final int BATCH_SIZE = 50;
    /** Safety cap on category pagination pages. */
    private static final int MAX_CAT_PAGES = 100;

    private final OkHttpClient httpClient;

    public ItemStatsScraper(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetches all equipment items from the OSRS wiki.
     *
     * @return list of parsed rows; may be empty on error
     * @throws IOException on HTTP or JSON parsing errors
     */
    public List<ItemStatsRow> fetchAll() throws IOException {
        List<String> titles = fetchTemplateTransclusions("Template:Infobox_Bonuses");
        System.out.println("  Template:Infobox_Bonuses is transcluded on " + titles.size() + " pages");
        if (titles.isEmpty()) return Collections.emptyList();

        List<ItemStatsRow> result = new ArrayList<>();
        for (int i = 0; i < titles.size(); i += BATCH_SIZE) {
            List<String> batch = titles.subList(i, Math.min(i + BATCH_SIZE, titles.size()));
            try {
                List<ItemStatsRow> rows = fetchAndParseBatch(batch);
                result.addAll(rows);
            } catch (IOException e) {
                System.err.println("  WARN: batch starting at " + i + " failed: " + e.getMessage());
            }
            // Polite rate-limiting
            try { Thread.sleep(100); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Step 1: all pages that transclude Template:Infobox_Bonuses
    // -------------------------------------------------------------------------

    private List<String> fetchTemplateTransclusions(String template) throws IOException {
        List<String> titles = new ArrayList<>();
        String eiContinue = null;
        int pages = 0;

        do {
            if (pages++ >= MAX_CAT_PAGES) {
                System.err.println("  WARN: hit MAX_CAT_PAGES for " + template);
                break;
            }
            HttpUrl.Builder urlBuilder = HttpUrl.parse(WIKI_API).newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("list", "embeddedin")
                    .addQueryParameter("eititle", template)
                    .addQueryParameter("eilimit", String.valueOf(CAT_LIMIT))
                    .addQueryParameter("einamespace", "0")  // main namespace only
                    .addQueryParameter("format", "json");
            if (eiContinue != null) {
                urlBuilder.addQueryParameter("eicontinue", eiContinue);
            }

            String body = get(urlBuilder.build().toString());
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();

            JsonObject query = root.getAsJsonObject("query");
            if (query == null) break;

            JsonArray members = query.getAsJsonArray("embeddedin");
            if (members != null) {
                for (JsonElement el : members) {
                    String title = el.getAsJsonObject().get("title").getAsString();
                    titles.add(title);
                }
            }

            // Pagination
            eiContinue = null;
            if (root.has("continue")) {
                JsonElement cont = root.getAsJsonObject("continue").get("eicontinue");
                if (cont != null && !cont.isJsonNull()) {
                    eiContinue = cont.getAsString();
                }
            }
        } while (eiContinue != null);

        return titles;
    }

    // -------------------------------------------------------------------------
    // Step 2 + 3: batch wikitext fetch + infobox parse
    // -------------------------------------------------------------------------

    private List<ItemStatsRow> fetchAndParseBatch(List<String> titles) throws IOException {
        // Build pipe-separated title list (wiki API supports | as separator)
        StringBuilder titlesParam = new StringBuilder();
        for (int i = 0; i < titles.size(); i++) {
            if (i > 0) titlesParam.append('|');
            titlesParam.append(titles.get(i));
        }

        HttpUrl url = HttpUrl.parse(WIKI_API).newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("prop", "revisions")
                .addQueryParameter("rvprop", "content")
                .addQueryParameter("rvslots", "main")
                .addQueryParameter("titles", titlesParam.toString())
                .addQueryParameter("format", "json")
                .build();

        String body = get(url.toString());
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject queryObj = root.getAsJsonObject("query");
        if (queryObj == null) return Collections.emptyList();

        JsonObject pagesObj = queryObj.getAsJsonObject("pages");
        if (pagesObj == null) return Collections.emptyList();

        List<ItemStatsRow> rows = new ArrayList<>();
        for (String pageId : pagesObj.keySet()) {
            JsonObject page = pagesObj.getAsJsonObject(pageId);
            if (page.has("missing")) continue;

            String title = page.has("title") ? page.get("title").getAsString() : null;
            if (title == null) continue;

            // Get wikitext from slots.main.* or revisions[0].*
            String wikitext = extractWikitext(page);
            if (wikitext == null || wikitext.isEmpty()) continue;

            ItemStatsRow row = parseInfoboxBonuses(title, wikitext);
            if (row != null) rows.add(row);
        }
        return rows;
    }

    private String extractWikitext(JsonObject page) {
        // MediaWiki 1.32+ uses slots.main.*
        JsonArray revisions = page.getAsJsonArray("revisions");
        if (revisions == null || revisions.size() == 0) return null;
        JsonObject rev = revisions.get(0).getAsJsonObject();

        // Try slots.main.content (rvslots=main)
        if (rev.has("slots")) {
            JsonObject slots = rev.getAsJsonObject("slots");
            if (slots.has("main")) {
                JsonObject main = slots.getAsJsonObject("main");
                if (main.has("content")) return main.get("content").getAsString();
                if (main.has("*")) return main.get("*").getAsString();
            }
        }
        // Fallback: rev.*
        if (rev.has("*")) return rev.get("*").getAsString();
        if (rev.has("content")) return rev.get("content").getAsString();
        return null;
    }

    // -------------------------------------------------------------------------
    // {{Infobox Bonuses}} parser
    // -------------------------------------------------------------------------

    /**
     * Extracts equipment stats from a page's wikitext. Returns {@code null} if
     * the page has no {@code {{Infobox Bonuses}}} template.
     */
    ItemStatsRow parseInfoboxBonuses(String title, String wikitext) {
        int start = indexOfIgnoreCase(wikitext, "{{Infobox Bonuses");
        if (start < 0) return null;

        // Find the closing `}}` (simple depth-0 scan)
        int depth = 0;
        int end = start;
        for (int i = start; i < wikitext.length() - 1; i++) {
            if (wikitext.charAt(i) == '{' && wikitext.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (wikitext.charAt(i) == '}' && wikitext.charAt(i + 1) == '}') {
                depth--;
                i++;
                if (depth == 0) { end = i + 1; break; }
            }
        }
        if (end <= start) return null;

        String block = wikitext.substring(start, end);
        ItemStatsRow row = new ItemStatsRow();
        row.pageName = title;

        // Parse each |field = value line
        String[] lines = block.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (!line.startsWith("|")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String field = line.substring(1, eq).trim().toLowerCase();
            String value = line.substring(eq + 1).trim();
            // Strip inline comments and wiki markup like [[...]]
            value = value.replaceAll("<!--.*?-->", "").replaceAll("\\[\\[[^|\\]]*\\|([^\\]]+)\\]\\]", "$1")
                    .replaceAll("\\[\\[([^\\]]+)\\]\\]", "$1").trim();
            if (value.isEmpty()) continue;

            switch (field) {
                case "slot":   row.slot = value.toLowerCase(); break;
                case "astab":  row.attackStab    = parseInt(value); break;
                case "aslash": row.attackSlash   = parseInt(value); break;
                case "acrush": row.attackCrush   = parseInt(value); break;
                case "amagic": row.attackMagic   = parseInt(value); break;
                case "arange": row.attackRanged  = parseInt(value); break;
                case "dstab":  row.defenceStab   = parseInt(value); break;
                case "dslash": row.defenceSlash  = parseInt(value); break;
                case "dcrush": row.defenceCrush  = parseInt(value); break;
                case "dmagic": row.defenceMagic  = parseInt(value); break;
                case "drange": row.defenceRanged = parseInt(value); break;
                case "str":    row.meleeStrength = parseInt(value); break;
                case "rstr":   row.rangedStrength = parseInt(value); break;
                case "mdmg":   row.magicDamage   = parseInt(value); break;
                case "prayer": row.prayerBonus   = parseInt(value); break;
                case "weight": row.weight        = parseDouble(value); break;
            }
        }

        // Must have a slot to be useful
        return (row.slot != null && !row.slot.isEmpty()) ? row : null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            if (response.body() == null) {
                throw new IOException("Empty body from " + url);
            }
            return response.body().string();
        }
    }

    private static int indexOfIgnoreCase(String text, String pattern) {
        return text.toLowerCase().indexOf(pattern.toLowerCase());
    }

    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        // Strip leading + sign and strip trailing non-numeric characters (e.g. "%")
        String clean = s.replaceAll("[^\\-0-9]", "");
        if (clean.isEmpty()) return 0;
        try { return Integer.parseInt(clean); } catch (NumberFormatException e) { return 0; }
    }

    private static double parseDouble(String s) {
        if (s == null || s.isEmpty()) return 0.0;
        String clean = s.replaceAll("[^\\-0-9.]", "");
        if (clean.isEmpty()) return 0.0;
        try { return Double.parseDouble(clean); } catch (NumberFormatException e) { return 0.0; }
    }

    // -------------------------------------------------------------------------
    // Data class
    // -------------------------------------------------------------------------

    /** One row of equipment stats parsed from an {{Infobox Bonuses}} template. */
    public static class ItemStatsRow {
        public String pageName;
        public String slot;
        public int attackStab, attackSlash, attackCrush, attackMagic, attackRanged;
        public int defenceStab, defenceSlash, defenceCrush, defenceMagic, defenceRanged;
        public int meleeStrength, magicDamage, rangedStrength, prayerBonus;
        public double weight;
    }
}
