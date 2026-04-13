package com.leaguesai.scraper;

import okhttp3.OkHttpClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the standalone wiki scraper tool.
 *
 * <p>Usage: {@code WikiScraper <openai-api-key> <output-db-path>}
 *
 * <p>Scrapes OSRS Wiki league task pages for Demonic Pacts League (Leagues VI),
 * normalises each row, optionally resolves tile coordinates, generates an
 * OpenAI embedding, and upserts everything into a local SQLite database.
 */
public class WikiScraper {

    private static final String WIKI_BASE = "https://oldschool.runescape.wiki/w/";

    private static final String[] TASK_PAGES = {
        "Demonic_Pacts_League/Tasks",
    };

    /** Rate-limit delay between embedding API calls (milliseconds). */
    private static final long EMBEDDING_SLEEP_MS = 100;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: WikiScraper <output-db-path> [openai-api-key] [--skip-tasks]");
            System.err.println("  --skip-tasks  Skip Demonic Pacts League task pages (use pre-launch).");
            System.err.println("  If no API key is provided, embeddings will be skipped.");
            System.exit(1);
        }

        String dbPath = null;
        String apiKey = null;
        boolean skipTasks = false;
        for (String arg : args) {
            if ("--skip-tasks".equals(arg)) {
                skipTasks = true;
            } else if (dbPath == null) {
                dbPath = arg;
            } else {
                apiKey = arg;
            }
        }
        if (dbPath == null) {
            System.err.println("Usage: WikiScraper <output-db-path> [openai-api-key] [--skip-tasks]");
            System.exit(1);
        }

        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        SqliteWriter writer = new SqliteWriter(dbFile);
        TaskItemExtractor taskItemExtractor = new TaskItemExtractor();
        OkHttpClient httpClient = new OkHttpClient();
        EmbeddingGenerator embedder = (apiKey != null && !apiKey.isEmpty())
                ? new EmbeddingGenerator(apiKey)
                : null;
        if (embedder == null) {
            System.out.println("No API key provided — skipping embedding generation.");
        }

        try {
            writer.initialize();
            System.out.println("Database initialised: " + dbFile.getAbsolutePath());
        } catch (SQLException e) {
            System.err.println("Failed to initialise database: " + e.getMessage());
            System.exit(1);
        }

        int totalTasks = 0;
        int totalErrors = 0;

        if (skipTasks) {
            System.out.println("Task scraping SKIPPED (--skip-tasks flag).");
        }

        for (String pagePath : skipTasks ? new String[0] : TASK_PAGES) {
            String url  = WIKI_BASE + pagePath;
            String area = deriveAreaName(pagePath);
            System.out.println("\nScraping: " + url);

            String html;
            try {
                Document doc = Jsoup.connect(url)
                        .userAgent("leagues-vi-ai-scraper/1.0 (github.com/leagues-vi-ai)")
                        .timeout(30_000)
                        .get();
                html = doc.outerHtml();
            } catch (IOException e) {
                System.err.println("  ERROR fetching page: " + e.getMessage());
                totalErrors++;
                continue;
            }

            List<HtmlParser.TaskRow> rows = HtmlParser.parseTaskTableRich(html);
            System.out.println("  Found " + rows.size() + " task rows");

            for (HtmlParser.TaskRow row : rows) {
                String name        = row.name;
                String description = row.description;
                String difficulty  = row.difficulty;
                int    points      = row.points;
                String rowArea     = row.area != null && !row.area.isEmpty() ? row.area : area;
                Map<String, Integer> skillsReq = TaskNormalizer.parseSkillRequirements(
                        row.requirements != null ? row.requirements : "");

                if (name == null || name.isEmpty()) {
                    continue;
                }

                // Resolve location from description or area name
                int[] location = LocationResolver.resolve(description);
                if (location == null) {
                    location = LocationResolver.resolve(rowArea);
                }

                // Generate embedding (with rate-limit sleep) — only if embedder available
                byte[] embedding = null;
                if (embedder != null) {
                    String embeddingText = name + ". " + description;
                    try {
                        embedding = embedder.generate(embeddingText);
                        Thread.sleep(EMBEDDING_SLEEP_MS);
                    } catch (IOException e) {
                        System.err.println("  WARN embedding failed for '" + name + "': " + e.getMessage());
                        totalErrors++;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                try {
                    String skillsJson   = SqliteWriter.stringIntMapToJson(skillsReq);
                    String locationJson = SqliteWriter.locationToJson(location);
                    String stableId = (row.taskId != null && !row.taskId.isEmpty())
                            ? "tbz-" + row.taskId
                            : java.util.UUID.nameUUIDFromBytes(name.getBytes()).toString();

                    // Extract item targets from task name + description
                    List<TaskItemExtractor.ItemTarget> itemTargets =
                            taskItemExtractor.extract(name, description);
                    String targetItemsJson = buildItemTargetsJson(itemTargets);

                    writer.upsertTaskWithId(
                            stableId,
                            name,
                            description,
                            difficulty,
                            points,
                            rowArea,
                            null,            // category — not parsed from wiki yet
                            skillsJson,
                            null,            // quests_required
                            null,            // tasks_required
                            null,            // items_required
                            locationJson,
                            null,            // target_npcs
                            null,            // target_objects
                            targetItemsJson,
                            url,
                            embedding);
                    totalTasks++;
                    System.out.println("  [OK] " + name);
                } catch (SQLException e) {
                    System.err.println("  ERROR writing task '" + name + "': " + e.getMessage());
                    totalErrors++;
                }
            }
        }

        System.out.println("\nTask scraping done. Total tasks: " + totalTasks);

        // Scrape item dependencies (crafting/smithing/fletching/herblore/cooking recipes)
        System.out.println("\nScraping item dependencies from Skill calc modules...");
        try {
            ItemDependencyScraper itemScraper = new ItemDependencyScraper(dbFile);
            int rows = itemScraper.scrape();
            System.out.println("Item dependencies written: " + rows);
        } catch (Exception e) {
            System.err.println("Item dependency scrape failed: " + e.getMessage());
            totalErrors++;
        }

        // Scrape equipment stats from OSRS Wiki (Category:Equipment) → items table
        System.out.println("\nScraping equipment stats from OSRS Wiki...");
        int totalItems = 0;
        try {
            ItemStatsScraper itemStatsScraper = new ItemStatsScraper(httpClient);
            List<ItemStatsScraper.ItemStatsRow> itemRows = itemStatsScraper.fetchAll();
            System.out.println("  Fetched " + itemRows.size() + " equipment rows from wiki");
            for (ItemStatsScraper.ItemStatsRow row : itemRows) {
                if (row.pageName == null || row.pageName.isEmpty()) continue;
                // Slug the page name to a stable id (lowercase, spaces→underscores, strip specials)
                String id = row.pageName.toLowerCase()
                        .replaceAll("[^a-z0-9]+", "_")
                        .replaceAll("^_|_$", "");
                String wikiUrl = "https://oldschool.runescape.wiki/w/" +
                        row.pageName.replace(" ", "_");
                // Normalize wiki slot name to GearSlot enum name
                String slot = normalizeSlot(row.slot);
                try {
                    writer.upsertItem(
                            id,
                            0,              // wikiItemId — not in Infobox Bonuses
                            row.pageName,
                            slot,
                            null,           // region — not per-item in wiki
                            row.attackStab, row.attackSlash, row.attackCrush,
                            row.attackMagic, row.attackRanged,
                            row.defenceStab, row.defenceSlash, row.defenceCrush,
                            row.defenceMagic, row.defenceRanged,
                            row.meleeStrength, row.magicDamage,
                            row.rangedStrength, row.prayerBonus,
                            row.weight,
                            null,           // skill_requirements — parsed separately
                            wikiUrl,
                            null            // embedding — not needed for gear lookup
                    );
                    totalItems++;
                } catch (SQLException e) {
                    System.err.println("  ERROR writing item '" + row.pageName + "': " + e.getMessage());
                    totalErrors++;
                }
            }
            System.out.println("  Equipment items written: " + totalItems);
        } catch (IOException e) {
            System.err.println("Equipment stats scrape failed: " + e.getMessage());
            totalErrors++;
        }

        writer.close();

        System.out.println("\nDone. Tasks: " + totalTasks + "  Items: " + totalItems + "  Errors: " + totalErrors);
        System.out.println("Database: " + dbFile.getAbsolutePath());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a JSON array string from a list of item targets.
     * Example: {@code [{"id":0,"name":"Bandos chestplate"}]}
     */
    private static String buildItemTargetsJson(List<TaskItemExtractor.ItemTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) sb.append(',');
            TaskItemExtractor.ItemTarget t = targets.get(i);
            sb.append("{\"id\":").append(t.wikiItemId)
              .append(",\"name\":\"").append(escapeJson(t.name)).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Maps an OSRS wiki {{Infobox Bonuses}} slot value to the GearSlot enum name
     * stored in the SQLite items table. Returns null for unmapped values so
     * GearRepository skips them gracefully.
     */
    private static String normalizeSlot(String wikiSlot) {
        if (wikiSlot == null) return null;
        switch (wikiSlot.toLowerCase()) {
            case "head":    return "HEAD";
            case "cape":    return "CAPE";
            case "neck":    return "AMULET";
            case "weapon":  return "WEAPON";
            case "2h":      return "WEAPON";
            case "shield":  return "SHIELD";
            case "body":    return "BODY";
            case "legs":    return "LEGS";
            case "hands":   return "HANDS";
            case "feet":    return "FEET";
            case "ring":    return "RING";
            case "ammo":    return "AMMO";
            default:        return null; // unrecognised slot — skip
        }
    }

    /** Extracts the area name from the last path segment, replacing underscores. */
    private static String deriveAreaName(String pagePath) {
        String[] parts = pagePath.split("/");
        return parts[parts.length - 1].replace('_', ' ');
    }

    private static int parsePoints(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        // Strip anything that isn't a digit
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
