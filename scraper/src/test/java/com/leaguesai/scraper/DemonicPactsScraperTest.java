package com.leaguesai.scraper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Scanner;

import static org.junit.Assert.*;

/**
 * Integration test covering the full phase-1 pipeline:
 * fixture HTML → HtmlParser → SqliteWriter → SQLite round-trip.
 *
 * <p>Does not call {@link DemonicPactsScraper#main(String[])} (which performs
 * live network calls). The main method is thin glue — its correctness follows
 * from the parser + writer behaviour verified here.
 */
public class DemonicPactsScraperTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String loadFixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/demonic_pacts/" + name)) {
            assertNotNull("Missing fixture: " + name, in);
            Scanner sc = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : "";
        }
    }

    @Test
    public void relicsRoundTrip_writesAllRowsWithTiers() throws Exception {
        File db = new File(tmp.getRoot(), "test.db");
        SqliteWriter writer = new SqliteWriter(db);
        writer.initialize();

        List<HtmlParser.RelicRow> relics = HtmlParser.parseRelicsPage(loadFixture("relics.html"));
        for (HtmlParser.RelicRow r : relics) {
            writer.upsertRelic(DemonicPactsScraper.slugify(r.name), r.name, r.tier, r.effect, 0, r.effect);
        }
        writer.close();

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM relics")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
        }

        // Verify tier + name for Grimoire specifically
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT tier, name FROM relics WHERE name='Grimoire'")) {
            assertTrue("Grimoire row present", rs.next());
            assertEquals(6, rs.getInt("tier"));
            assertEquals("Grimoire", rs.getString("name"));
        }
    }

    @Test
    public void areasRoundTrip_filtersMisthalin() throws Exception {
        File db = new File(tmp.getRoot(), "test.db");
        SqliteWriter writer = new SqliteWriter(db);
        writer.initialize();

        List<HtmlParser.AreaRow> areas = HtmlParser.parseAreasPage(loadFixture("areas.html"));
        for (HtmlParser.AreaRow a : areas) {
            writer.upsertArea(a.id, a.name, a.unlockCost, null, null);
        }
        writer.close();

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id FROM areas WHERE id='Misthalin'")) {
            assertFalse("Misthalin must not be written", rs.next());
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM areas")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
        }
    }

    @Test
    public void pactsRoundTrip_idIsLowercaseCodePrefixed() throws Exception {
        File db = new File(tmp.getRoot(), "test.db");
        SqliteWriter writer = new SqliteWriter(db);
        writer.initialize();

        List<HtmlParser.PactRow> pacts = HtmlParser.parsePactsPage(loadFixture("pacts.html"));
        for (HtmlParser.PactRow p : pacts) {
            writer.upsertPact("pact-" + p.code.toLowerCase(), p.code, null, p.effect,
                    "https://example/pact", null, null);
        }
        writer.close();

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name, node_type, effect FROM pacts ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("pact-aa", rs.getString("id"));
            assertEquals("AA", rs.getString("name"));
            assertNull("Phase 1: node_type must be null", rs.getString("node_type"));
            assertTrue(rs.getString("effect").contains("50% chance to Regenerate"));

            assertTrue(rs.next());
            assertEquals("pact-b1", rs.getString("id"));

            assertFalse("Only 2 valid pact rows in fixture", rs.next());
        }
    }

    @Test
    public void slugify_lowercasesAndStripsPunctuation() {
        assertEquals("grimoire", DemonicPactsScraper.slugify("Grimoire"));
        assertEquals("endless-harvest", DemonicPactsScraper.slugify("Endless Harvest"));
        // Apostrophes are non-alphanumeric, so they collapse into the dash
        // separator alongside the space → "butler-s-bell". Slugs stay stable
        // round-to-round as long as the rules don't change.
        assertEquals("butler-s-bell", DemonicPactsScraper.slugify("Butler's Bell"));
        assertEquals("", DemonicPactsScraper.slugify(null));
    }
}
