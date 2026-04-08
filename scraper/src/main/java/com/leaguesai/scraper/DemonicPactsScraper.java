package com.leaguesai.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Phase 1 scraper for Leagues VI (Demonic Pacts League). Populates the
 * {@code relics}, {@code areas}, and {@code pacts} tables from the OSRS Wiki.
 *
 * <p>Runs alongside the existing {@link WikiScraper} (which handles tasks)
 * and writes into the same SQLite database. Phase 1 deliberately skips
 * task scraping — the wiki's Tasks page is only a 4-row stub until launch
 * day (2026-04-15). See CLAUDE.md "Phase 2 TODO".
 *
 * <p>Usage: {@code DemonicPactsScraper <output-db-path>}.
 */
public class DemonicPactsScraper {

    private static final String RELICS_URL = "https://oldschool.runescape.wiki/w/Demonic_Pacts_League/Relics";
    private static final String AREAS_URL  = "https://oldschool.runescape.wiki/w/Demonic_Pacts_League/Areas";
    private static final String PACTS_URL  = "https://oldschool.runescape.wiki/w/Demonic_Pacts_League/Demonic_Pacts";

    private static final String USER_AGENT = "leagues-vi-ai-scraper/1.0 (github.com/leagues-vi-ai)";

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: DemonicPactsScraper <output-db-path>");
            System.exit(1);
        }
        String dbPath = args[0];
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null) parent.mkdirs();

        SqliteWriter writer = new SqliteWriter(dbFile);
        try {
            writer.initialize();
        } catch (SQLException e) {
            System.err.println("Failed to initialise database: " + e.getMessage());
            System.exit(1);
        }

        int relicCount = 0;
        int areaCount = 0;
        int pactCount = 0;
        int errors = 0;

        // Relics --------------------------------------------------------------
        System.out.println("Scraping " + RELICS_URL);
        try {
            String html = fetch(RELICS_URL);
            List<HtmlParser.RelicRow> rows = HtmlParser.parseRelicsPage(html);
            System.out.println("  Found " + rows.size() + " relic rows");
            for (HtmlParser.RelicRow r : rows) {
                try {
                    String id = slugify(r.name);
                    // Effects column kept as plain flattened text — callers
                    // that want structured data should re-scrape in phase 2.
                    writer.upsertRelic(id, r.name, r.tier, r.effect, 0, r.effect);
                    relicCount++;
                    System.out.println("  [OK] T" + r.tier + " " + r.name);
                } catch (SQLException e) {
                    System.err.println("  ERROR writing relic '" + r.name + "': " + e.getMessage());
                    errors++;
                }
            }
        } catch (IOException e) {
            System.err.println("  ERROR fetching relics page: " + e.getMessage());
            errors++;
        }

        // Areas ---------------------------------------------------------------
        System.out.println("\nScraping " + AREAS_URL);
        try {
            String html = fetch(AREAS_URL);
            List<HtmlParser.AreaRow> rows = HtmlParser.parseAreasPage(html);
            System.out.println("  Found " + rows.size() + " area rows");
            for (HtmlParser.AreaRow a : rows) {
                try {
                    writer.upsertArea(a.id, a.name, a.unlockCost, null, null);
                    areaCount++;
                    System.out.println("  [OK] " + a.name);
                } catch (SQLException e) {
                    System.err.println("  ERROR writing area '" + a.name + "': " + e.getMessage());
                    errors++;
                }
            }
        } catch (IOException e) {
            System.err.println("  ERROR fetching areas page: " + e.getMessage());
            errors++;
        }

        // Pacts ---------------------------------------------------------------
        System.out.println("\nScraping " + PACTS_URL);
        try {
            String html = fetch(PACTS_URL);
            List<HtmlParser.PactRow> rows = HtmlParser.parsePactsPage(html);
            System.out.println("  Found " + rows.size() + " pact rows");
            for (HtmlParser.PactRow p : rows) {
                try {
                    String id = "pact-" + p.code.toLowerCase(Locale.ROOT);
                    // Phase 1: name == code. Phase 2 may replace with a
                    // human-readable label once the wiki adds one.
                    writer.upsertPact(id, p.code, null, p.effect, PACTS_URL, null, null);
                    pactCount++;
                    System.out.println("  [OK] Pact " + p.code);
                } catch (SQLException e) {
                    System.err.println("  ERROR writing pact '" + p.code + "': " + e.getMessage());
                    errors++;
                }
            }
        } catch (IOException e) {
            System.err.println("  ERROR fetching pacts page: " + e.getMessage());
            errors++;
        }

        writer.close();

        System.out.println("\nDone. Relics: " + relicCount
                + "  Areas: " + areaCount
                + "  Pacts: " + pactCount
                + "  Errors: " + errors);
        System.out.println("Database: " + dbFile.getAbsolutePath());
    }

    private static String fetch(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(30_000)
                .get();
        return doc.outerHtml();
    }

    /** Lowercase, replace non-alphanumerics with dashes — stable relic IDs. */
    static String slugify(String name) {
        if (name == null) return "";
        return name.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
