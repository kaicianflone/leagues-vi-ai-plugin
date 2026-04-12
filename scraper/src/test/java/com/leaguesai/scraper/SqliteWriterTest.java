package com.leaguesai.scraper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SqliteWriter}.
 *
 * Uses a temp directory so each test gets a fresh database file, avoiding
 * cross-test state. No network I/O.
 */
public class SqliteWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SqliteWriter writer;
    private File dbFile;

    @Before
    public void setUp() throws Exception {
        dbFile = tmp.newFile("test-leagues.db");
        writer = new SqliteWriter(dbFile);
        writer.initialize();
    }

    @After
    public void tearDown() {
        writer.close();
    }

    // -------------------------------------------------------------------------
    // Schema tests
    // -------------------------------------------------------------------------

    @Test
    public void testInitialize_createsItemsTable() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='items'");
            assertTrue("items table should exist after initialize()", rs.next());
            assertEquals("items", rs.getString("name"));
        }
    }

    @Test
    public void testInitialize_createsTasksTable() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='tasks'");
            assertTrue("tasks table should exist after initialize()", rs.next());
        }
    }

    @Test
    public void testInitialize_idempotent() throws Exception {
        // Calling initialize() twice must not throw or corrupt the schema.
        writer.close();
        writer = new SqliteWriter(dbFile);
        writer.initialize(); // second call — should be no-op due to IF NOT EXISTS
    }

    // -------------------------------------------------------------------------
    // upsertItem roundtrip
    // -------------------------------------------------------------------------

    @Test
    public void testUpsertItem_roundtrip() throws Exception {
        writer.upsertItem(
            "bandos_chestplate",  // id
            22720,                // wikiItemId
            "Bandos chestplate",  // name
            "BODY",               // slot
            "God Wars Dungeon",   // region
            4,                    // attackStab
            6,                    // attackSlash
            8,                    // attackCrush
            -6,                   // attackMagic
            -3,                   // attackRanged
            81,                   // defenceStab
            79,                   // defenceSlash
            86,                   // defenceCrush
            -15,                  // defenceMagic
            8,                    // defenceRanged
            112,                  // meleeStrength
            0,                    // magicDamage
            0,                    // rangedStrength
            1,                    // prayerBonus
            9.0,                  // weight
            "{\"defence\":65}",   // skillRequirementsJson
            "https://oldschool.runescape.wiki/w/Bandos_chestplate",  // wikiUrl
            null                  // embedding
        );

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                "SELECT * FROM items WHERE id='bandos_chestplate'");
            assertTrue("upsertItem should have inserted a row", rs.next());

            assertEquals("bandos_chestplate", rs.getString("id"));
            assertEquals(22720, rs.getInt("wiki_item_id"));
            assertEquals("Bandos chestplate", rs.getString("name"));
            assertEquals("BODY", rs.getString("slot"));
            assertEquals("God Wars Dungeon", rs.getString("region"));
            assertEquals(4, rs.getInt("attack_stab"));
            assertEquals(6, rs.getInt("attack_slash"));
            assertEquals(8, rs.getInt("attack_crush"));
            assertEquals(-6, rs.getInt("attack_magic"));
            assertEquals(-3, rs.getInt("attack_ranged"));
            assertEquals(81, rs.getInt("defence_stab"));
            assertEquals(79, rs.getInt("defence_slash"));
            assertEquals(86, rs.getInt("defence_crush"));
            assertEquals(-15, rs.getInt("defence_magic"));
            assertEquals(8, rs.getInt("defence_ranged"));
            assertEquals(112, rs.getInt("melee_strength"));
            assertEquals(0, rs.getInt("magic_damage"));
            assertEquals(0, rs.getInt("ranged_strength"));
            assertEquals(1, rs.getInt("prayer_bonus"));
            assertEquals(9.0, rs.getDouble("weight"), 0.001);
            assertEquals("{\"defence\":65}", rs.getString("skill_requirements"));
            assertEquals(
                "https://oldschool.runescape.wiki/w/Bandos_chestplate",
                rs.getString("wiki_url"));
            assertNull("embedding should be null", rs.getBytes("embedding"));
        }
    }

    @Test
    public void testUpsertItem_replaceOnConflict() throws Exception {
        // Insert once, then re-insert with different stats — should replace.
        writer.upsertItem("twisted_bow", 0, "Twisted bow", "WEAPON",
            null, 0, 0, 0, 0, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1.81, null, null, null);
        writer.upsertItem("twisted_bow", 20997, "Twisted bow", "WEAPON",
            null, 0, 0, 0, 0, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1.81, null, null, null);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM items WHERE id='twisted_bow'");
            assertTrue(rs.next());
            assertEquals("INSERT OR REPLACE should leave exactly 1 row", 1, rs.getInt("cnt"));

            rs = stmt.executeQuery("SELECT wiki_item_id FROM items WHERE id='twisted_bow'");
            assertTrue(rs.next());
            assertEquals(20997, rs.getInt("wiki_item_id"));
        }
    }

    @Test
    public void testUpsertItem_nullRegion() throws Exception {
        writer.upsertItem("fire_cape", 0, "Fire cape", "CAPE",
            null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 11, 0, 0, 2, 0.453,
            "{\"defence\":0}", null, null);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT region FROM items WHERE id='fire_cape'");
            assertTrue(rs.next());
            assertNull("region should be stored as NULL when not provided", rs.getString("region"));
        }
    }
}
