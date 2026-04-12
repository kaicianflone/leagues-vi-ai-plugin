package com.leaguesai.scraper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes 5 Skill calc Lua modules from the OSRS Wiki MediaWiki API and
 * writes the resulting item dependency rows into the {@code item_dependencies}
 * SQLite table.
 *
 * <p>Modules scraped:
 * <ul>
 *   <li>Module:Skill_calc/Smithing  → obtain_method = SMITHED</li>
 *   <li>Module:Skill_calc/Crafting  → obtain_method = CRAFTED</li>
 *   <li>Module:Skill_calc/Fletching → obtain_method = FLETCHED</li>
 *   <li>Module:Skill_calc/Herblore  → obtain_method = HERBLORE</li>
 *   <li>Module:Skill_calc/Cooking   → obtain_method = COOKED</li>
 * </ul>
 *
 * <p>The Lua wikitext is parsed with regex — no Lua interpreter needed for
 * the simple table structure used by these modules.
 *
 * <p>HTTP errors are retried up to 3 times with 1 s backoff. Malformed Lua
 * records are skipped with a warning.
 */
public class ItemDependencyScraper {

    private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
    private static final String USER_AGENT =
            "leagues-vi-ai-scraper/1.0 (github.com/kaicianflone/leagues-vi-ai-plugin)";

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_SLEEP_MS = 1_000L;

    /** Each entry: [moduleName, obtainMethod, skillName] */
    private static final String[][] MODULES = {
        { "Module:Skill_calc/Smithing",  "SMITHED",  "SMITHING"  },
        { "Module:Skill_calc/Crafting",  "CRAFTED",  "CRAFTING"  },
        { "Module:Skill_calc/Fletching", "FLETCHED", "FLETCHING" },
        { "Module:Skill_calc/Herblore",  "HERBLORE", "HERBLORE"  },
        { "Module:Skill_calc/Cooking",   "COOKED",   "COOKING"   },
    };

    private static final String INSERT_SQL =
        "INSERT OR REPLACE INTO item_dependencies" +
        "  (item_id, item_name, obtain_method, source_id, source_name," +
        "   skill_required, skill_level, qty_needed, output_qty, area_required)" +
        " VALUES (?,?,?,?,?,?,?,?,?,NULL)";

    private final OkHttpClient httpClient;
    private final File dbFile;

    /**
     * Constructs the scraper. SQLite connection is opened lazily in
     * {@link #scrape()}.
     *
     * @param dbFile path to the SQLite database that already has an
     *               {@code item_dependencies} table (created by
     *               {@code ItemDependencyGraph.loadFromDb()})
     */
    public ItemDependencyScraper(File dbFile) {
        this.dbFile = dbFile;
        this.httpClient = new OkHttpClient();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetches all 5 Skill calc modules and writes rows into the database.
     *
     * @return total number of rows written
     */
    public int scrape() {
        int totalRows = 0;

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + dbFile.getAbsolutePath())) {

            // Ensure the table exists (idempotent).
            conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS item_dependencies (" +
                "  item_id        TEXT NOT NULL," +
                "  item_name      TEXT NOT NULL," +
                "  obtain_method  TEXT NOT NULL," +
                "  source_id      TEXT," +
                "  source_name    TEXT," +
                "  skill_required TEXT," +
                "  skill_level    INTEGER DEFAULT 0," +
                "  qty_needed     INTEGER DEFAULT 1," +
                "  output_qty     INTEGER DEFAULT 1," +
                "  area_required  TEXT," +
                "  PRIMARY KEY (item_id, obtain_method, source_id)" +
                ")"
            );

            for (String[] module : MODULES) {
                String moduleName  = module[0];
                String obtainMethod = module[1];
                String skillName   = module[2];

                System.out.println("  Fetching " + moduleName + " ...");
                String wikitext = null;
                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    try {
                        wikitext = fetchWikitext(moduleName);
                        break;
                    } catch (IOException e) {
                        System.err.println("  WARN: attempt " + attempt + "/" + MAX_RETRIES +
                                " failed for " + moduleName + ": " + e.getMessage());
                        if (attempt < MAX_RETRIES) {
                            try { Thread.sleep(RETRY_SLEEP_MS); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
                if (wikitext == null) {
                    System.err.println("  ERROR: all retries exhausted for " + moduleName +
                            ", skipping module");
                    continue;
                }

                List<LuaRecord> records = parseLua(wikitext);
                System.out.println("  Parsed " + records.size() + " records from " + moduleName);

                try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                    for (LuaRecord rec : records) {
                        for (LuaMaterial mat : rec.materials) {
                            try {
                                String itemId    = slugify(rec.name);
                                String sourceId  = slugify(mat.name);
                                if (itemId.isEmpty() || sourceId.isEmpty()) continue;

                                ps.setString(1, itemId);
                                ps.setString(2, rec.name);
                                ps.setString(3, obtainMethod);
                                ps.setString(4, sourceId);
                                ps.setString(5, mat.name);
                                ps.setString(6, skillName);
                                ps.setInt(7, rec.level);
                                ps.setInt(8, mat.quantity);
                                ps.setInt(9, rec.outputQuantity);
                                ps.addBatch();
                                totalRows++;
                            } catch (SQLException e) {
                                System.err.println("  WARN: failed to insert record for '" +
                                        rec.name + "' material '" + mat.name + "': " + e.getMessage());
                            }
                        }
                    }
                    ps.executeBatch();
                }
            }

        } catch (SQLException e) {
            System.err.println("ItemDependencyScraper: database error: " + e.getMessage());
        }

        return totalRows;
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    /**
     * Fetches the raw wikitext content of a MediaWiki module via the API.
     *
     * @param moduleName e.g. {@code "Module:Skill_calc/Smithing"}
     * @return wikitext string
     * @throws IOException on HTTP or JSON errors
     */
    private String fetchWikitext(String moduleName) throws IOException {
        String url = WIKI_API +
                "?action=query&prop=revisions&rvprop=content" +
                "&titles=" + encode(moduleName) +
                "&format=json";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("MediaWiki API returned HTTP " + response.code() +
                        " for " + moduleName);
            }
            if (response.body() == null) {
                throw new IOException("Empty response body for " + moduleName);
            }
            String body = response.body().string();
            return extractWikitext(body, moduleName);
        }
    }

    /**
     * Extracts the wikitext from the MediaWiki API JSON response.
     * The content is at: {@code query.pages.<pageId>.revisions[0].*}
     */
    private String extractWikitext(String json, String moduleName) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject pages = root.getAsJsonObject("query").getAsJsonObject("pages");
            // There will be exactly one page entry; its key is the page ID.
            for (String pageId : pages.keySet()) {
                JsonObject page = pages.getAsJsonObject(pageId);
                if (!page.has("revisions")) {
                    throw new IOException("No revisions found for " + moduleName);
                }
                JsonObject rev = page.getAsJsonArray("revisions")
                        .get(0).getAsJsonObject();
                // The content key is literally "*"
                JsonElement content = rev.get("*");
                if (content == null || content.isJsonNull()) {
                    throw new IOException("No content ('*') in revision for " + moduleName);
                }
                return content.getAsString();
            }
            throw new IOException("No pages in API response for " + moduleName);
        } catch (Exception e) {
            throw new IOException("Failed to parse API response for " + moduleName +
                    ": " + e.getMessage(), e);
        }
    }

    /** Minimal URL-encoding for the module title parameter. */
    private static String encode(String s) {
        return s.replace(" ", "%20").replace(":", "%3A").replace("/", "%2F");
    }

    // -------------------------------------------------------------------------
    // Lua parsing
    // -------------------------------------------------------------------------

    /**
     * A single product record from the Lua module.
     */
    private static class LuaRecord {
        String name = "";
        int level = 0;
        int outputQuantity = 1;
        List<LuaMaterial> materials = new ArrayList<>();
    }

    /** A single material (ingredient) within a product record. */
    private static class LuaMaterial {
        String name = "";
        int quantity = 1;
    }

    // Matches a top-level record block: everything between outer { } pairs.
    // We split by looking for { at depth 0 in the top-level list.
    private static final Pattern NAME_PAT = Pattern.compile(
            "name\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern LEVEL_PAT = Pattern.compile(
            "level\\s*=\\s*(\\d+)");
    private static final Pattern OUTPUT_QTY_PAT = Pattern.compile(
            "outputQuantity\\s*=\\s*(\\d+)");
    private static final Pattern MATERIALS_BLOCK_PAT = Pattern.compile(
            "materials\\s*=\\s*\\{([^}]*(?:\\{[^}]*\\}[^}]*)*)\\}",
            Pattern.DOTALL);
    private static final Pattern MATERIAL_ENTRY_PAT = Pattern.compile(
            "\\{([^}]+)\\}", Pattern.DOTALL);
    private static final Pattern QTY_PAT = Pattern.compile(
            "quantity\\s*=\\s*(\\d+)");

    /**
     * Parses a Lua module table into a list of {@link LuaRecord}s.
     *
     * <p>The approach: find the outer {@code return { … }} block, then split
     * it into top-level record entries by tracking brace depth manually.
     * For each record we use regex to extract name, level, outputQuantity,
     * and the materials sub-block.
     */
    List<LuaRecord> parseLua(String lua) {
        List<LuaRecord> records = new ArrayList<>();

        // Find the outer return { … } block
        int returnIdx = lua.indexOf("return");
        if (returnIdx < 0) return records;
        int start = lua.indexOf('{', returnIdx);
        if (start < 0) return records;
        int end = findMatchingBrace(lua, start);
        if (end < 0) return records;

        // Extract top-level record blocks within the outer { }
        String body = lua.substring(start + 1, end);
        List<String> topLevelBlocks = splitTopLevelBlocks(body);

        for (String block : topLevelBlocks) {
            try {
                LuaRecord rec = parseRecord(block);
                if (rec != null && !rec.name.isEmpty() && !rec.materials.isEmpty()) {
                    records.add(rec);
                }
            } catch (Exception e) {
                System.err.println("  WARN: skipping malformed Lua record: " + e.getMessage());
            }
        }

        return records;
    }

    /**
     * Parses a single top-level record block (the text between outer braces).
     */
    private LuaRecord parseRecord(String block) {
        LuaRecord rec = new LuaRecord();

        Matcher nameMatcher = NAME_PAT.matcher(block);
        if (!nameMatcher.find()) return null;
        rec.name = nameMatcher.group(1).trim();

        Matcher levelMatcher = LEVEL_PAT.matcher(block);
        if (levelMatcher.find()) {
            rec.level = Integer.parseInt(levelMatcher.group(1));
        }

        Matcher outputMatcher = OUTPUT_QTY_PAT.matcher(block);
        if (outputMatcher.find()) {
            rec.outputQuantity = Integer.parseInt(outputMatcher.group(1));
            if (rec.outputQuantity <= 0) rec.outputQuantity = 1;
        }

        Matcher materialsMatcher = MATERIALS_BLOCK_PAT.matcher(block);
        if (materialsMatcher.find()) {
            String materialsBlock = materialsMatcher.group(1);
            Matcher entryMatcher = MATERIAL_ENTRY_PAT.matcher(materialsBlock);
            while (entryMatcher.find()) {
                String entry = entryMatcher.group(1);
                LuaMaterial mat = new LuaMaterial();

                Matcher matNameMatcher = NAME_PAT.matcher(entry);
                if (!matNameMatcher.find()) continue;
                mat.name = matNameMatcher.group(1).trim();

                Matcher qtyMatcher = QTY_PAT.matcher(entry);
                if (qtyMatcher.find()) {
                    mat.quantity = Integer.parseInt(qtyMatcher.group(1));
                    if (mat.quantity <= 0) mat.quantity = 1;
                }

                if (!mat.name.isEmpty()) {
                    rec.materials.add(mat);
                }
            }
        }

        return rec;
    }

    /**
     * Splits the body of the outer Lua table into top-level record strings,
     * tracking brace depth to avoid splitting on nested braces (e.g. materials
     * sub-tables).
     */
    private static List<String> splitTopLevelBlocks(String body) {
        List<String> blocks = new ArrayList<>();
        int depth = 0;
        int blockStart = -1;

        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    blockStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && blockStart >= 0) {
                    blocks.add(body.substring(blockStart + 1, i));
                    blockStart = -1;
                }
            }
        }
        return blocks;
    }

    /**
     * Returns the index of the closing brace matching the opening brace at
     * {@code openIdx}, or {@code -1} if not found.
     */
    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // Slugify — mirrors ItemDependencyGraph.slugify()
    // -------------------------------------------------------------------------

    static String slugify(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT)
                   .replace("'", "")
                   .replace(" ", "_")
                   .replaceAll("[^a-z0-9_]", "");
    }
}
