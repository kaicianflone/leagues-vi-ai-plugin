package com.leaguesai.scraper;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HtmlParser}.
 */
public class HtmlParserTest {

    // ------------------------------------------------------------------
    // Happy-path: simple 2-column wikitable with 2 data rows
    // ------------------------------------------------------------------

    private static final String SIMPLE_TABLE_HTML =
        "<html><body>" +
        "<table class=\"wikitable\">" +
        "  <tr><th>Task</th><th>Difficulty</th></tr>" +
        "  <tr><td>Chop a tree</td><td>Easy</td></tr>" +
        "  <tr><td>Mine iron ore</td><td>Medium</td></tr>" +
        "</table>" +
        "</body></html>";

    @Test
    public void testSimpleTable_rowCount() {
        List<Map<String, String>> rows = HtmlParser.parseTaskTable(SIMPLE_TABLE_HTML);
        assertEquals("Expected 2 data rows", 2, rows.size());
    }

    @Test
    public void testSimpleTable_firstRowValues() {
        List<Map<String, String>> rows = HtmlParser.parseTaskTable(SIMPLE_TABLE_HTML);
        Map<String, String> first = rows.get(0);
        assertEquals("Chop a tree", first.get("Task"));
        assertEquals("Easy", first.get("Difficulty"));
    }

    @Test
    public void testSimpleTable_secondRowValues() {
        List<Map<String, String>> rows = HtmlParser.parseTaskTable(SIMPLE_TABLE_HTML);
        Map<String, String> second = rows.get(1);
        assertEquals("Mine iron ore", second.get("Task"));
        assertEquals("Medium", second.get("Difficulty"));
    }

    // ------------------------------------------------------------------
    // Multiple tables are concatenated
    // ------------------------------------------------------------------

    private static final String TWO_TABLES_HTML =
        "<html><body>" +
        "<table class=\"wikitable\">" +
        "  <tr><th>Task</th><th>Points</th></tr>" +
        "  <tr><td>Task A</td><td>10</td></tr>" +
        "</table>" +
        "<table class=\"wikitable\">" +
        "  <tr><th>Task</th><th>Points</th></tr>" +
        "  <tr><td>Task B</td><td>20</td></tr>" +
        "</table>" +
        "</body></html>";

    @Test
    public void testMultipleTables_concatenated() {
        List<Map<String, String>> rows = HtmlParser.parseTaskTable(TWO_TABLES_HTML);
        assertEquals("Both tables should be concatenated", 2, rows.size());
        assertEquals("Task A", rows.get(0).get("Task"));
        assertEquals("Task B", rows.get(1).get("Task"));
    }

    // ------------------------------------------------------------------
    // Empty / header-only table → empty list
    // ------------------------------------------------------------------

    private static final String HEADER_ONLY_HTML =
        "<html><body>" +
        "<table class=\"wikitable\">" +
        "  <tr><th>Task</th><th>Difficulty</th></tr>" +
        "</table>" +
        "</body></html>";

    @Test
    public void testHeaderOnlyTable_returnsEmptyList() {
        List<Map<String, String>> rows = HtmlParser.parseTaskTable(HEADER_ONLY_HTML);
        assertNotNull(rows);
        assertTrue("Header-only table should yield no rows", rows.isEmpty());
    }

    // ------------------------------------------------------------------
    // Non-wikitable tables should be ignored
    // ------------------------------------------------------------------

    private static final String NON_WIKITABLE_HTML =
        "<html><body>" +
        "<table class=\"other-table\">" +
        "  <tr><th>Task</th><th>Difficulty</th></tr>" +
        "  <tr><td>Ignored</td><td>Easy</td></tr>" +
        "</table>" +
        "</body></html>";

    @Test
    public void testNonWikitableIgnored() {
        List<Map<String, String>> rows = HtmlParser.parseTaskTable(NON_WIKITABLE_HTML);
        assertTrue("Non-wikitable tables should be ignored", rows.isEmpty());
    }

    // ------------------------------------------------------------------
    // Empty HTML → empty list (no NPE)
    // ------------------------------------------------------------------

    @Test
    public void testEmptyHtml_returnsEmptyList() {
        List<Map<String, String>> rows = HtmlParser.parseTaskTable("");
        assertNotNull(rows);
        assertTrue(rows.isEmpty());
    }

    // ------------------------------------------------------------------
    // Demonic Pacts League fixtures (phase 1 scraper)
    // ------------------------------------------------------------------

    private String loadFixture(String name) {
        try (InputStream in = getClass().getResourceAsStream("/demonic_pacts/" + name)) {
            assertNotNull("Missing fixture: " + name, in);
            Scanner sc = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : "";
        } catch (IOException e) {
            fail("Could not read fixture " + name + ": " + e.getMessage());
            return ""; // unreachable
        }
    }

    // ---- Relics ----

    @Test
    public void testParseRelicsPage_rowCountAndTiers() {
        String html = loadFixture("relics.html");
        List<HtmlParser.RelicRow> rows = HtmlParser.parseRelicsPage(html);
        assertEquals("3 relic rows across tier 1 and tier 6", 3, rows.size());

        // Tiers preserved from h3 id
        List<Integer> tiers = new ArrayList<>();
        for (HtmlParser.RelicRow r : rows) tiers.add(r.tier);
        assertTrue("Tier 1 present", tiers.contains(1));
        assertTrue("Tier 6 present", tiers.contains(6));
    }

    @Test
    public void testParseRelicsPage_namesExtractedFromAnchors() {
        String html = loadFixture("relics.html");
        List<HtmlParser.RelicRow> rows = HtmlParser.parseRelicsPage(html);

        List<String> names = new ArrayList<>();
        for (HtmlParser.RelicRow r : rows) names.add(r.name);
        assertTrue("Endless Harvest parsed", names.contains("Endless Harvest"));
        assertTrue("Abundance parsed", names.contains("Abundance"));
        assertTrue("Grimoire parsed", names.contains("Grimoire"));
    }

    @Test
    public void testParseRelicsPage_bulletListFlattened() {
        String html = loadFixture("relics.html");
        List<HtmlParser.RelicRow> rows = HtmlParser.parseRelicsPage(html);

        HtmlParser.RelicRow endless = null;
        for (HtmlParser.RelicRow r : rows) {
            if ("Endless Harvest".equals(r.name)) endless = r;
        }
        assertNotNull(endless);
        assertTrue("Contains first bullet",
                endless.effect.contains("Resources gathered from Fishing, Woodcutting, and Mining are multiplied by 2"));
        assertTrue("Contains second bullet",
                endless.effect.contains("XP is granted for all additional resources gathered"));
        assertTrue("Bullets joined with separator", endless.effect.contains(" / "));
    }

    @Test
    public void testParseRelicsPage_plainTextEffectCell() {
        String html = loadFixture("relics.html");
        List<HtmlParser.RelicRow> rows = HtmlParser.parseRelicsPage(html);

        HtmlParser.RelicRow abundance = null;
        for (HtmlParser.RelicRow r : rows) {
            if ("Abundance".equals(r.name)) abundance = r;
        }
        assertNotNull(abundance);
        assertEquals("All non-combat skills are permanently boosted by 10.", abundance.effect);
    }

    // ---- Areas ----

    @Test
    public void testParseAreasPage_skipsGeneralInfoAndMisthalin() {
        String html = loadFixture("areas.html");
        List<HtmlParser.AreaRow> rows = HtmlParser.parseAreasPage(html);

        // Fixture has 5 h2 sections (General_information, Varlamore, Karamja,
        // Wilderness, Misthalin). Parser must drop 2 (general + misthalin).
        assertEquals("3 playable areas after filtering", 3, rows.size());

        List<String> ids = new ArrayList<>();
        for (HtmlParser.AreaRow a : rows) ids.add(a.id);
        assertFalse("General information filtered out", ids.contains("General_information"));
        assertFalse("Misthalin filtered out (not accessible)", ids.contains("Misthalin"));
        assertTrue(ids.contains("Varlamore"));
        assertTrue(ids.contains("Karamja"));
        assertTrue(ids.contains("Wilderness"));
    }

    @Test
    public void testParseAreasPage_unlockCostIsZeroInPhase1() {
        String html = loadFixture("areas.html");
        List<HtmlParser.AreaRow> rows = HtmlParser.parseAreasPage(html);
        for (HtmlParser.AreaRow a : rows) {
            assertEquals("Phase 1 leaves unlockCost at 0 (wiki has no numbers yet)",
                    0, a.unlockCost);
        }
    }

    // ---- Pacts ----

    @Test
    public void testParsePactsPage_extractsCodeFromImageFilename() {
        String html = loadFixture("pacts.html");
        List<HtmlParser.PactRow> rows = HtmlParser.parsePactsPage(html);
        // 3 rows in the fixture but one has no img and must be dropped.
        assertEquals("Row without image skipped", 2, rows.size());

        List<String> codes = new ArrayList<>();
        for (HtmlParser.PactRow p : rows) codes.add(p.code);
        assertTrue("Code AA extracted", codes.contains("AA"));
        assertTrue("Code B1 extracted", codes.contains("B1"));
    }

    @Test
    public void testParsePactsPage_effectIncludesFootnoteParagraph() {
        String html = loadFixture("pacts.html");
        List<HtmlParser.PactRow> rows = HtmlParser.parsePactsPage(html);

        HtmlParser.PactRow aa = null;
        for (HtmlParser.PactRow p : rows) {
            if ("AA".equals(p.code)) aa = p;
        }
        assertNotNull(aa);
        assertTrue("Main effect preserved",
                aa.effect.contains("50% chance to Regenerate runes, ammo and charges"));
        assertTrue("Footnote paragraph preserved",
                aa.effect.contains("Regenerate: chance to generate an additional resource spent"));
    }

    @Test
    public void testParsePactsPage_plainEffectWithoutBullets() {
        String html = loadFixture("pacts.html");
        List<HtmlParser.PactRow> rows = HtmlParser.parsePactsPage(html);

        HtmlParser.PactRow b1 = null;
        for (HtmlParser.PactRow p : rows) {
            if ("B1".equals(p.code)) b1 = p;
        }
        assertNotNull(b1);
        assertTrue(b1.effect.contains("Whenever your combat spells Regenerate runes"));
    }

    @Test
    public void testParseRelicsPage_emptyHtmlReturnsEmptyList() {
        assertTrue(HtmlParser.parseRelicsPage("").isEmpty());
    }

    @Test
    public void testParseAreasPage_emptyHtmlReturnsEmptyList() {
        assertTrue(HtmlParser.parseAreasPage("").isEmpty());
    }

    @Test
    public void testParsePactsPage_emptyHtmlReturnsEmptyList() {
        assertTrue(HtmlParser.parsePactsPage("").isEmpty());
    }
}
