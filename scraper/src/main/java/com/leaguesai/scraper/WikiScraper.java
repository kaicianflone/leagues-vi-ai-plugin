package com.leaguesai.scraper;

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
            System.err.println("Usage: WikiScraper <output-db-path> [openai-api-key]");
            System.err.println("  If no API key is provided, embeddings will be skipped");
            System.err.println("  (vector search disabled, but tasks/areas/relics still load).");
            System.exit(1);
        }

        String dbPath = args[0];
        String apiKey = args.length >= 2 ? args[1] : null;

        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        SqliteWriter writer = new SqliteWriter(dbFile);
        TaskItemExtractor taskItemExtractor = new TaskItemExtractor();
        // TODO: run ItemStatsScraper separately via 'scrape-items' Gradle task
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

        for (String pagePath : TASK_PAGES) {
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

        System.out.println("Task scrape complete. Item targets extracted (id=0). Run 'scrape-items' task to resolve wiki IDs and full stats.");

        writer.close();

        System.out.println("\nDone. Tasks written: " + totalTasks + "  Errors: " + totalErrors);
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
