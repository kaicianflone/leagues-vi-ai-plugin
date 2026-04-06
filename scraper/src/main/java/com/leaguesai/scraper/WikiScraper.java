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
 * <p>Scrapes OSRS Wiki league task pages (currently targeting Leagues V /
 * Trailblazer Reloaded — URLs will be swapped to Leagues VI on launch day),
 * normalises each row, optionally resolves tile coordinates, generates an
 * OpenAI embedding, and upserts everything into a local SQLite database.
 */
public class WikiScraper {

    private static final String WIKI_BASE = "https://oldschool.runescape.wiki/w/";

    // Leagues V placeholder pages — swap paths for Leagues VI on launch day.
    private static final String[] TASK_PAGES = {
        "Trailblazer_Reloaded_League/Tasks/Misthalin",
        "Trailblazer_Reloaded_League/Tasks/Karamja",
        "Trailblazer_Reloaded_League/Tasks/Asgarnia",
    };

    /** Rate-limit delay between embedding API calls (milliseconds). */
    private static final long EMBEDDING_SLEEP_MS = 100;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: WikiScraper <openai-api-key> <output-db-path>");
            System.exit(1);
        }

        String apiKey    = args[0];
        String dbPath    = args[1];

        File dbFile = new File(dbPath);
        dbFile.getParentFile().mkdirs();

        SqliteWriter      writer    = new SqliteWriter(dbFile);
        EmbeddingGenerator embedder = new EmbeddingGenerator(apiKey);

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

            List<Map<String, String>> rows = HtmlParser.parseTaskTable(html);
            System.out.println("  Found " + rows.size() + " task rows");

            for (Map<String, String> row : rows) {
                String name        = row.getOrDefault("Task", row.getOrDefault("Name", ""));
                String description = row.getOrDefault("Description", row.getOrDefault("Details", ""));
                String rawDiff     = row.getOrDefault("Difficulty", "");
                String rawPoints   = row.getOrDefault("Points", "0");
                String rawSkills   = row.getOrDefault("Requirements", row.getOrDefault("Skills", ""));

                if (name.isEmpty()) {
                    continue; // skip rows without a task name
                }

                String difficulty = TaskNormalizer.normalizeDifficulty(rawDiff);
                int    points     = parsePoints(rawPoints);
                Map<String, Integer> skillsReq = TaskNormalizer.parseSkillRequirements(rawSkills);

                // Resolve location from description or area name
                int[] location = LocationResolver.resolve(description);
                if (location == null) {
                    location = LocationResolver.resolve(area);
                }

                // Generate embedding (with rate-limit sleep)
                String embeddingText = name + ". " + description;
                byte[] embedding = null;
                try {
                    embedding = embedder.generate(embeddingText);
                    Thread.sleep(EMBEDDING_SLEEP_MS);
                } catch (IOException e) {
                    System.err.println("  WARN embedding failed for '" + name + "': " + e.getMessage());
                    totalErrors++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                try {
                    writer.upsertTask(name, description, difficulty, points, area,
                            skillsReq, location, embedding, url);
                    totalTasks++;
                    System.out.println("  [OK] " + name);
                } catch (SQLException e) {
                    System.err.println("  ERROR writing task '" + name + "': " + e.getMessage());
                    totalErrors++;
                }
            }
        }

        writer.close();

        System.out.println("\nDone. Tasks written: " + totalTasks + "  Errors: " + totalErrors);
        System.out.println("Database: " + dbFile.getAbsolutePath());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
