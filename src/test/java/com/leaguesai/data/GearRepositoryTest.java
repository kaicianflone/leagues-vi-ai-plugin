package com.leaguesai.data;

import com.leaguesai.data.model.GearItem;
import com.leaguesai.data.model.GearSlot;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.Assert.*;

public class GearRepositoryTest {

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @Test
    public void classpath_seed_loads_items() {
        // Non-existent DB path → falls back to gear.json
        File nonExistentDb = new File(tmpFolder.getRoot(), "missing.db");
        GearRepository repo = new GearRepository(nonExistentDb);

        List<GearItem> all = repo.listAll();
        assertTrue("Expected at least 30 items from gear.json, got: " + all.size(), all.size() >= 30);
    }

    @Test
    public void findById_returns_correct_item() {
        File nonExistentDb = new File(tmpFolder.getRoot(), "missing.db");
        GearRepository repo = new GearRepository(nonExistentDb);

        GearItem item = repo.findById("bandos_chestplate");
        assertNotNull("Expected bandos_chestplate to be found", item);
        assertEquals("Bandos chestplate", item.getName());
        assertEquals(GearSlot.BODY, item.getSlot());
        assertEquals("Expected defence 65 requirement", 65, (int) item.getSkillRequirements().get("defence"));
    }

    @Test
    public void findById_returns_null_for_unknown() {
        File nonExistentDb = new File(tmpFolder.getRoot(), "missing.db");
        GearRepository repo = new GearRepository(nonExistentDb);

        assertNull(repo.findById("not_an_item"));
    }

    @Test
    public void findBySlot_filters_correctly() {
        File nonExistentDb = new File(tmpFolder.getRoot(), "missing.db");
        GearRepository repo = new GearRepository(nonExistentDb);

        List<GearItem> bodyItems = repo.findBySlot(GearSlot.BODY);
        assertFalse("Expected at least one BODY item", bodyItems.isEmpty());
        for (GearItem item : bodyItems) {
            assertEquals("All items should be BODY slot", GearSlot.BODY, item.getSlot());
        }
    }

    @Test
    public void db_items_take_precedence_over_classpath() throws Exception {
        File dbFile = tmpFolder.newFile("items.db");

        // Create items table and insert one row
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE items (" +
                    "id TEXT PRIMARY KEY, " +
                    "wiki_item_id INTEGER, " +
                    "name TEXT, " +
                    "slot TEXT, " +
                    "region TEXT, " +
                    "attack_stab INTEGER, " +
                    "attack_slash INTEGER, " +
                    "attack_crush INTEGER, " +
                    "attack_magic INTEGER, " +
                    "attack_ranged INTEGER, " +
                    "defence_stab INTEGER, " +
                    "defence_slash INTEGER, " +
                    "defence_crush INTEGER, " +
                    "defence_magic INTEGER, " +
                    "defence_ranged INTEGER, " +
                    "melee_strength INTEGER, " +
                    "magic_damage INTEGER, " +
                    "ranged_strength INTEGER, " +
                    "prayer_bonus INTEGER, " +
                    "weight REAL, " +
                    "skill_requirements TEXT, " +
                    "wiki_url TEXT" +
                    ")");

            stmt.execute("INSERT INTO items VALUES (" +
                    "'test_helmet', 12345, 'Test Helmet', 'HEAD', null, " +
                    "1, 2, 3, -5, 0, " +
                    "10, 11, 12, -3, 5, " +
                    "0, 0, 0, 2, 1.5, " +
                    "'{\"defence\": 40}', 'https://example.com/test_helmet'" +
                    ")");
        }

        GearRepository repo = new GearRepository(dbFile);

        // DB has 1 item — classpath items must NOT be loaded
        List<GearItem> all = repo.listAll();
        assertEquals("Expected exactly 1 item from DB", 1, all.size());

        GearItem item = repo.findById("test_helmet");
        assertNotNull("Expected test_helmet from DB", item);
        assertEquals("Test Helmet", item.getName());
        assertEquals(GearSlot.HEAD, item.getSlot());
        assertEquals(40, (int) item.getSkillRequirements().get("defence"));

        // Classpath item must not bleed through
        assertNull("bandos_chestplate should not be present when DB is used",
                repo.findById("bandos_chestplate"));
    }
}
