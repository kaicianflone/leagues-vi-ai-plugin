package com.leaguesai.agent;

import com.leaguesai.data.model.ItemDependency;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.Assert.*;

public class ItemDependencyGraphTest {

    /** Temp DB file used by tests that need a real SQLite database. */
    private File tempDb;

    @Before
    public void setUp() throws Exception {
        tempDb = File.createTempFile("item_dep_test_", ".db");
        tempDb.deleteOnExit();
    }

    @After
    public void tearDown() {
        if (tempDb != null) {
            tempDb.delete();
        }
    }

    // -------------------------------------------------------------------------
    // 1. Empty graph — expand returns empty list
    // -------------------------------------------------------------------------

    @Test
    public void testEmptyGraph_expandReturnsEmpty() {
        ItemDependencyGraph graph = new ItemDependencyGraph(new File("/nonexistent/path/to.db"));
        graph.loadFromDb();

        List<ItemDependency> result = graph.expand("any_id");

        assertNotNull(result);
        assertTrue("expand on empty graph should return empty list", result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // 2. Empty graph — findItemByName returns null
    // -------------------------------------------------------------------------

    @Test
    public void testEmptyGraph_findItemByNameReturnsNull() {
        ItemDependencyGraph graph = new ItemDependencyGraph(new File("/nonexistent/path/to.db"));
        graph.loadFromDb();

        assertNull("findItemByName on empty graph should return null",
                graph.findItemByName("anything"));
    }

    // -------------------------------------------------------------------------
    // 3. isRecursable
    // -------------------------------------------------------------------------

    @Test
    public void testIsRecursable() {
        assertTrue("SMITHED should be recursable", ObtainMethod.SMITHED.isRecursable());
        assertTrue("CRAFTED should be recursable", ObtainMethod.CRAFTED.isRecursable());
        assertTrue("FLETCHED should be recursable", ObtainMethod.FLETCHED.isRecursable());
        assertTrue("HERBLORE should be recursable", ObtainMethod.HERBLORE.isRecursable());
        assertTrue("COOKED should be recursable", ObtainMethod.COOKED.isRecursable());

        assertFalse("DROPPED should NOT be recursable", ObtainMethod.DROPPED.isRecursable());
        assertFalse("QUEST_REWARD should NOT be recursable", ObtainMethod.QUEST_REWARD.isRecursable());
        assertFalse("SHOP should NOT be recursable", ObtainMethod.SHOP.isRecursable());
        assertFalse("SKILL_REWARD should NOT be recursable", ObtainMethod.SKILL_REWARD.isRecursable());
    }

    // -------------------------------------------------------------------------
    // 4. slugify
    // -------------------------------------------------------------------------

    @Test
    public void testSlugify() {
        assertEquals("rune_platebody", ItemDependencyGraph.slugify("Rune platebody"));
        assertEquals("barrows_gloves", ItemDependencyGraph.slugify("Barrows gloves"));
        assertEquals("amulet_of_torture", ItemDependencyGraph.slugify("Amulet of torture"));
        assertEquals("dragons", ItemDependencyGraph.slugify("Dragon's"));
        assertEquals("", ItemDependencyGraph.slugify(null));
    }

    // -------------------------------------------------------------------------
    // 5. findItemByName — null and blank inputs
    // -------------------------------------------------------------------------

    @Test
    public void testFindItemByName_nullAndBlank() throws Exception {
        ItemDependencyGraph graph = new ItemDependencyGraph(tempDb);
        graph.loadFromDb();

        assertNull("null name should return null", graph.findItemByName(null));
        assertNull("empty string should return null", graph.findItemByName(""));
        assertNull("blank string should return null", graph.findItemByName("   "));
    }

    // -------------------------------------------------------------------------
    // 6. expand with a real DB — BFS recursion fires for SMITHED, not DROPPED
    // -------------------------------------------------------------------------

    @Test
    public void testExpandWithRealDb() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb.getAbsolutePath())) {
            createTable(conn);
            // item_a is SMITHED from ingredient item_b
            insertRow(conn, "item_a", "Item A", "SMITHED", "item_b", "Item B",
                    "Smithing", 50, 1, 1, null);
            // item_b is DROPPED from "Giant spider" (terminal)
            insertRow(conn, "item_b", "Item B", "DROPPED", "giant_spider", "Giant Spider",
                    null, 0, 1, 1, null);
        }

        ItemDependencyGraph graph = new ItemDependencyGraph(tempDb);
        graph.loadFromDb();

        assertFalse("graph should not be empty after loading", graph.isEmpty());
        assertEquals("graph should have 2 rows total", 2, graph.size());

        List<ItemDependency> result = graph.expand("item_a");

        // BFS starts at item_a → finds SMITHED dep on item_b → recurses into item_b
        // item_b is DROPPED (terminal) → no further recursion
        // Expected: item_a's dep row + item_b's dep row = 2 entries
        assertEquals("expand should return 2 entries", 2, result.size());

        // item_b dep should appear first (BFS adds item_a deps, then enqueues item_b;
        // item_a dep is added to result, then item_b dep is added when item_b is processed)
        // Actually: item_a is processed first → item_a's row added, item_b enqueued
        // Then item_b is processed → item_b's row added, no further enqueue (DROPPED)
        // So order is: item_a-dep, item_b-dep
        boolean hasItemADep = false;
        boolean hasItemBDep = false;
        for (ItemDependency dep : result) {
            if ("item_a".equals(dep.getItemId())) hasItemADep = true;
            if ("item_b".equals(dep.getItemId())) hasItemBDep = true;
        }
        assertTrue("result should contain item_a's dependency row", hasItemADep);
        assertTrue("result should contain item_b's dependency row (BFS recursed into ingredient)", hasItemBDep);

        // Verify DROPPED dep's sourceId is the monster name, not further expanded
        ItemDependency bDep = null;
        for (ItemDependency dep : result) {
            if ("item_b".equals(dep.getItemId())) {
                bDep = dep;
                break;
            }
        }
        assertNotNull(bDep);
        assertEquals("DROPPED source_id should be entity name", "giant_spider", bDep.getSourceId());
        assertFalse("DROPPED should not be recursable", bDep.getObtainMethod().isRecursable());
    }

    // -------------------------------------------------------------------------
    // 7. Cycle detection — no infinite loop
    // -------------------------------------------------------------------------

    @Test
    public void testCycleDetection() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb.getAbsolutePath())) {
            createTable(conn);
            // A CRAFTED from B, B CRAFTED from A — mutual cycle
            insertRow(conn, "item_a", "Item A", "CRAFTED", "item_b", "Item B",
                    "Crafting", 40, 1, 1, null);
            insertRow(conn, "item_b", "Item B", "CRAFTED", "item_a", "Item A",
                    "Crafting", 40, 1, 1, null);
        }

        ItemDependencyGraph graph = new ItemDependencyGraph(tempDb);
        graph.loadFromDb();

        // Must terminate — visited set prevents re-processing
        List<ItemDependency> result = graph.expand("item_a");

        assertNotNull("result should not be null", result);
        // Cycle: item_a visited → item_b enqueued → item_b visited → item_a already visited → done
        // Result: item_a's dep + item_b's dep = 2 rows
        assertTrue("result size should be <= 2 (cycle terminated)", result.size() <= 2);
    }

    // -------------------------------------------------------------------------
    // 8. Depth limit — chain of 25, result capped at MAX_DEPTH (20)
    // -------------------------------------------------------------------------

    @Test
    public void testDepthLimit() throws Exception {
        int chainLength = 25;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb.getAbsolutePath())) {
            createTable(conn);
            // item_0 SMITHED from item_1, item_1 SMITHED from item_2, ..., item_24 DROPPED
            for (int i = 0; i < chainLength - 1; i++) {
                String itemId = "item_" + i;
                String itemName = "Item " + i;
                String srcId = "item_" + (i + 1);
                String srcName = "Item " + (i + 1);
                insertRow(conn, itemId, itemName, "SMITHED", srcId, srcName,
                        "Smithing", 30, 1, 1, null);
            }
            // Terminal at the end of the chain
            int last = chainLength - 1;
            insertRow(conn, "item_" + last, "Item " + last, "DROPPED", "some_monster", "Some Monster",
                    null, 0, 1, 1, null);
        }

        ItemDependencyGraph graph = new ItemDependencyGraph(tempDb);
        graph.loadFromDb();

        List<ItemDependency> result = graph.expand("item_0");

        // MAX_DEPTH = 20: BFS expands item_0 (depth 0), enqueues item_1 at depth 1,
        // ..., item_19 at depth 19 is processed, tries to enqueue item_20 but depth==20 → stopped.
        // Each visited node contributes 1 dep row.
        // Visited nodes: item_0 through item_19 = 20 nodes → 20 rows.
        assertEquals("result size should equal MAX_DEPTH (20)", 20, result.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void createTable(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
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
        }
    }

    private void insertRow(Connection conn,
                           String itemId, String itemName,
                           String obtainMethod,
                           String sourceId, String sourceName,
                           String skillRequired, int skillLevel,
                           int qtyNeeded, int outputQty,
                           String areaRequired) throws Exception {
        String sql = "INSERT OR REPLACE INTO item_dependencies " +
                "(item_id, item_name, obtain_method, source_id, source_name, " +
                " skill_required, skill_level, qty_needed, output_qty, area_required) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId);
            ps.setString(2, itemName);
            ps.setString(3, obtainMethod);
            ps.setString(4, sourceId);
            ps.setString(5, sourceName);
            ps.setString(6, skillRequired);
            ps.setInt(7, skillLevel);
            ps.setInt(8, qtyNeeded);
            ps.setInt(9, outputQty);
            ps.setString(10, areaRequired);
            ps.executeUpdate();
        }
    }
}
