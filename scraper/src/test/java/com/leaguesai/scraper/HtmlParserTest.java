package com.leaguesai.scraper;

import org.junit.Test;

import java.util.List;
import java.util.Map;

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
}
