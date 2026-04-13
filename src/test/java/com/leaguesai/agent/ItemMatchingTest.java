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

import static org.junit.Assert.*;

/**
 * Tests for {@link ItemDependencyGraph#findLongestMatchingItem} covering:
 * <ul>
 *   <li>Exact substring matching (pass 2)</li>
 *   <li>Bag-of-words / word-order-independent matching (pass 1)</li>
 *   <li>Stop-word filtering ("of", "the", etc.)</li>
 *   <li>Longest-match preference when multiple items match</li>
 *   <li>Catalog items (from {@code items} table) are also searchable</li>
 * </ul>
 */
public class ItemMatchingTest {

    private File tempDb;
    private ItemDependencyGraph graph;

    @Before
    public void setUp() throws Exception {
        tempDb = File.createTempFile("item_match_test_", ".db");
        tempDb.deleteOnExit();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb.getAbsolutePath())) {
            createTables(conn);
            // Seed item_dependencies with a few items
            insertDep(conn, "rune_platebody",  "Rune platebody",  "DROPPED");
            insertDep(conn, "staff_of_air",    "Staff of air",    "SHOP");
            insertDep(conn, "dragon_scimitar", "Dragon scimitar", "SHOP");
            insertDep(conn, "staff",           "Staff",           "SHOP"); // shorter name conflict
            insertDep(conn, "magic_longbow",   "Magic longbow",   "SHOP");
            insertDep(conn, "yew_longbow",     "Yew longbow",     "SHOP");
        }

        graph = new ItemDependencyGraph(tempDb);
        graph.loadFromDb();
    }

    @After
    public void tearDown() {
        if (tempDb != null) tempDb.delete();
    }

    // -------------------------------------------------------------------------
    // Pass 1: exact substring match
    // -------------------------------------------------------------------------

    @Test
    public void exactSubstring_staffOfAir_matches() {
        ItemDependency dep = graph.findLongestMatchingItem("i need a staff of air");
        assertNotNull(dep);
        assertEquals("staff_of_air", dep.getItemId());
    }

    @Test
    public void exactSubstring_dragonScimitar_matches() {
        ItemDependency dep = graph.findLongestMatchingItem("get dragon scimitar please");
        assertNotNull(dep);
        assertEquals("dragon_scimitar", dep.getItemId());
    }

    @Test
    public void exactSubstring_runePlatebody_matches() {
        ItemDependency dep = graph.findLongestMatchingItem("farm rune platebody");
        assertNotNull(dep);
        assertEquals("rune_platebody", dep.getItemId());
    }

    @Test
    public void exactSubstring_longestMatchWins() {
        // Phrase contains "staff of air" AND "staff" — longer should win
        // (sorted longest-first, so "staff of air" wins over "staff")
        ItemDependency dep = graph.findLongestMatchingItem("get a staff of air for magic");
        assertNotNull(dep);
        assertEquals("staff_of_air", dep.getItemId());
    }

    // -------------------------------------------------------------------------
    // Pass 1 (bag-of-words): word-order independence
    // -------------------------------------------------------------------------

    @Test
    public void bagOfWords_airStaff_matchesStaffOfAir() {
        // "air staff" — reversed word order relative to "staff of air"
        // Pass 1 (bag-of-words): content words ["staff","air"] both appear in phrase
        ItemDependency dep = graph.findLongestMatchingItem("make an air staff");
        assertNotNull("'air staff' should match 'Staff of air' via bag-of-words", dep);
        assertEquals("staff_of_air", dep.getItemId());
    }

    @Test
    public void bagOfWords_longbowMagic_matchesMagicLongbow() {
        ItemDependency dep = graph.findLongestMatchingItem("fletch a longbow magic");
        assertNotNull("reversed order should match", dep);
        assertEquals("magic_longbow", dep.getItemId());
    }

    @Test
    public void bagOfWords_scimitarDragon_matchesDragonScimitar() {
        ItemDependency dep = graph.findLongestMatchingItem("i need scimitar dragon for slayer");
        assertNotNull(dep);
        assertEquals("dragon_scimitar", dep.getItemId());
    }

    @Test
    public void bagOfWords_stopWordIgnored() {
        // "staff of air" → content words ["staff","air"] (ignoring "of")
        // "air the staff" should also match because content words are the same
        ItemDependency dep = graph.findLongestMatchingItem("get air the staff");
        assertNotNull("stop word 'the' should be ignored", dep);
        assertEquals("staff_of_air", dep.getItemId());
    }

    @Test
    public void bagOfWords_requiresAllContentWords() {
        // "longbow" alone should NOT match "Magic longbow" in bag-of-words
        // because "magic" is not in the phrase (bag-of-words requires ALL content words)
        // BUT it might match "Yew longbow" via bag-of-words if "yew" is also missing.
        // Actually "longbow" alone is a single-content-word name if "longbow" is an item…
        // In our test db it's NOT an item. So result should be null.
        ItemDependency dep = graph.findLongestMatchingItem("i need a longbow");
        // "longbow" appears in both "Magic longbow" and "Yew longbow" as content words,
        // but bag-of-words requires ALL content words → needs "magic" or "yew" too.
        // No item named just "longbow" exists → should return null.
        assertNull("bare 'longbow' should not match multi-word items", dep);
    }

    @Test
    public void singleWordItem_staff_matchesViaSubstring() {
        // "staff" is a single-word item in our test db.
        // Bag-of-words pass skips single-content-word items (< 2 required).
        // Substring pass catches it.
        ItemDependency dep = graph.findLongestMatchingItem("i need a staff for magic training");
        assertNotNull("single-word item 'staff' should match via substring pass", dep);
        // But "staff of air" should NOT match here because neither "of" nor "air" is in phrase.
        // So we should get the shorter "staff" item (not staff_of_air).
        assertEquals("staff", dep.getItemId());
    }

    @Test
    public void nullPhrase_returnsNull() {
        assertNull(graph.findLongestMatchingItem(null));
        assertNull(graph.findLongestMatchingItem(""));
    }

    @Test
    public void noMatch_returnsNull() {
        assertNull(graph.findLongestMatchingItem("i want to eat an apple"));
    }

    // -------------------------------------------------------------------------
    // Catalog items (items table) are also found
    // -------------------------------------------------------------------------

    @Test
    public void catalogItem_seededInItemsTable_isFound() throws Exception {
        // Add a catalog item not in item_dependencies
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb.getAbsolutePath())) {
            conn.createStatement().execute(
                "INSERT OR REPLACE INTO items (id, name, wiki_url, wiki_item_id) " +
                "VALUES ('abyssal_whip', 'Abyssal whip', 'https://oldschool.runescape.wiki/w/Abyssal_whip', 4151)"
            );
        }
        // Reload graph to pick up new catalog item
        graph.loadFromDb();

        ItemDependency dep = graph.findLongestMatchingItem("get abyssal whip for melee");
        assertNotNull("Catalog item should be findable", dep);
        assertEquals("abyssal_whip", dep.getItemId());
    }

    // -------------------------------------------------------------------------
    // CraftableItemsCatalog: bag-of-words works for all alias phrases
    // -------------------------------------------------------------------------

    @Test
    public void allCatalogItems_atLeastOnePhraseFindable() throws Exception {
        // Seed all catalog items into item_dependencies for this test
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb.getAbsolutePath())) {
            for (CraftableItemsCatalog.ItemEntry item : CraftableItemsCatalog.ALL) {
                String id = item.getWikiSlug().toLowerCase().replace('-', '_');
                insertDep(conn, id, item.getDisplayName(), "SHOP");
            }
        }
        graph.loadFromDb();

        for (CraftableItemsCatalog.ItemEntry item : CraftableItemsCatalog.ALL) {
            // At least the display name itself should be findable when embedded in a phrase
            String phrase = "make " + item.getDisplayName().toLowerCase();
            ItemDependency dep = graph.findLongestMatchingItem(phrase);
            assertNotNull(
                "Display name '" + item.getDisplayName() + "' should be found in phrase: " + phrase,
                dep
            );
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void createTables(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS item_dependencies (" +
                "  item_id TEXT NOT NULL, item_name TEXT NOT NULL, " +
                "  obtain_method TEXT NOT NULL, source_id TEXT, source_name TEXT, " +
                "  skill_required TEXT, skill_level INTEGER DEFAULT 0, " +
                "  qty_needed INTEGER DEFAULT 1, output_qty INTEGER DEFAULT 1, " +
                "  area_required TEXT, PRIMARY KEY (item_id, obtain_method, source_id)" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS items (" +
                "  id TEXT PRIMARY KEY, name TEXT, wiki_url TEXT, wiki_item_id INTEGER DEFAULT 0" +
                ")"
            );
        }
    }

    private void insertDep(Connection conn, String itemId, String itemName,
                           String obtainMethod) throws Exception {
        String sql = "INSERT OR REPLACE INTO item_dependencies " +
                "(item_id, item_name, obtain_method, source_id, source_name, " +
                " skill_required, skill_level, qty_needed, output_qty, area_required) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId);
            ps.setString(2, itemName);
            ps.setString(3, obtainMethod);
            ps.setString(4, null);
            ps.setString(5, null);
            ps.setString(6, null);
            ps.setInt(7, 0);
            ps.setInt(8, 1);
            ps.setInt(9, 1);
            ps.setString(10, null);
            ps.executeUpdate();
        }
    }
}
