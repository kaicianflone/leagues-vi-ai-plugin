package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class DatabaseLoaderTest {

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    private File dbFile;

    @Before
    public void setUp() throws Exception {
        dbFile = tmpFolder.newFile("test.db");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            try (Statement stmt = conn.createStatement()) {
                // Create tasks table
                stmt.execute("CREATE TABLE tasks (" +
                        "id TEXT PRIMARY KEY, " +
                        "name TEXT, " +
                        "description TEXT, " +
                        "difficulty TEXT, " +
                        "points INTEGER, " +
                        "area TEXT, " +
                        "category TEXT, " +
                        "skills_required TEXT, " +
                        "quests_required TEXT, " +
                        "tasks_required TEXT, " +
                        "items_required TEXT, " +
                        "location TEXT, " +
                        "target_npcs TEXT, " +
                        "target_objects TEXT, " +
                        "target_items TEXT, " +
                        "wiki_url TEXT, " +
                        "embedding BLOB" +
                        ")");

                // Create areas table
                stmt.execute("CREATE TABLE areas (" +
                        "id TEXT PRIMARY KEY, " +
                        "name TEXT, " +
                        "unlock_cost INTEGER, " +
                        "unlock_requires TEXT, " +
                        "region_ids TEXT" +
                        ")");

                // Create relics table
                stmt.execute("CREATE TABLE relics (" +
                        "id TEXT PRIMARY KEY, " +
                        "name TEXT, " +
                        "tier INTEGER, " +
                        "description TEXT, " +
                        "unlock_cost INTEGER, " +
                        "effects TEXT" +
                        ")");
            }

            // Insert a task with all JSON fields
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tasks (id, name, description, difficulty, points, area, category, " +
                    "skills_required, quests_required, tasks_required, items_required, location, " +
                    "target_npcs, target_objects, target_items, wiki_url) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, "task-1");
                ps.setString(2, "Kill a Goblin");
                ps.setString(3, "Defeat a goblin in combat");
                ps.setString(4, "EASY");
                ps.setInt(5, 10);
                ps.setString(6, "misthalin");
                ps.setString(7, "combat");
                ps.setString(8, "{\"Attack\": 1, \"Strength\": 5}");
                ps.setString(9, "[\"Cook's Assistant\"]");
                ps.setString(10, "[]");
                ps.setString(11, "{\"Bronze sword\": 1}");
                ps.setString(12, "{\"x\": 3222, \"y\": 3218, \"plane\": 0}");
                ps.setString(13, "[{\"id\": 2, \"name\": \"Goblin\"}]");
                ps.setString(14, "[]");
                ps.setString(15, "[]");
                ps.setString(16, "https://oldschool.runescape.wiki/w/Goblin");
                ps.executeUpdate();
            }

            // Insert a second task with null JSON fields (edge case)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tasks (id, name, description, difficulty, points, area, category) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, "task-2");
                ps.setString(2, "Simple Task");
                ps.setString(3, "No frills");
                ps.setString(4, "MEDIUM");
                ps.setInt(5, 25);
                ps.setString(6, "asgarnia");
                ps.setString(7, "general");
                ps.executeUpdate();
            }

            // Insert an area
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO areas (id, name, unlock_cost, unlock_requires, region_ids) VALUES (?,?,?,?,?)")) {
                ps.setString(1, "area-misthalin");
                ps.setString(2, "Misthalin");
                ps.setInt(3, 0);
                ps.setString(4, "[]");
                ps.setString(5, "[12850, 12851, 12852]");
                ps.executeUpdate();
            }

            // Insert a relic
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO relics (id, name, tier, description, unlock_cost, effects) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, "relic-dragon-slayer");
                ps.setString(2, "Dragon Slayer");
                ps.setInt(3, 1);
                ps.setString(4, "Massively increased damage against dragons.");
                ps.setInt(5, 500);
                ps.setString(6, "{\"dragon_damage_bonus\": 5}");
                ps.executeUpdate();
            }
        }
    }

    @Test
    public void testLoadTasks() throws Exception {
        DatabaseLoader loader = new DatabaseLoader(dbFile);
        List<Task> tasks = loader.loadTasks();

        assertEquals(2, tasks.size());

        // Find task-1 (order may vary)
        Task t1 = tasks.stream().filter(t -> "task-1".equals(t.getId())).findFirst().orElse(null);
        assertNotNull("task-1 should be present", t1);

        assertEquals("Kill a Goblin", t1.getName());
        assertEquals("Defeat a goblin in combat", t1.getDescription());
        assertEquals(com.leaguesai.data.model.Difficulty.EASY, t1.getDifficulty());
        assertEquals(10, t1.getPoints());
        assertEquals("misthalin", t1.getArea());
        assertEquals("combat", t1.getCategory());
        assertEquals("https://oldschool.runescape.wiki/w/Goblin", t1.getWikiUrl());

        // Skills required: {"Attack": 1, "Strength": 5}
        assertNotNull(t1.getSkillsRequired());
        assertEquals(Integer.valueOf(1), t1.getSkillsRequired().get("Attack"));
        assertEquals(Integer.valueOf(5), t1.getSkillsRequired().get("Strength"));

        // Quests required
        assertNotNull(t1.getQuestsRequired());
        assertEquals(1, t1.getQuestsRequired().size());
        assertEquals("Cook's Assistant", t1.getQuestsRequired().get(0));

        // Tasks required (empty)
        assertNotNull(t1.getTasksRequired());
        assertTrue(t1.getTasksRequired().isEmpty());

        // Items required
        assertNotNull(t1.getItemsRequired());
        assertEquals(Integer.valueOf(1), t1.getItemsRequired().get("Bronze sword"));

        // Location / WorldPoint
        WorldPoint loc = t1.getLocation();
        assertNotNull(loc);
        assertEquals(3222, loc.getX());
        assertEquals(3218, loc.getY());
        assertEquals(0, loc.getPlane());

        // NPC targets
        assertNotNull(t1.getTargetNpcs());
        assertEquals(1, t1.getTargetNpcs().size());
        assertEquals(2, t1.getTargetNpcs().get(0).getId());
        assertEquals("Goblin", t1.getTargetNpcs().get(0).getName());

        // Object/item targets empty
        assertNotNull(t1.getTargetObjects());
        assertTrue(t1.getTargetObjects().isEmpty());
        assertNotNull(t1.getTargetItems());
        assertTrue(t1.getTargetItems().isEmpty());

        // Check task-2 handles null JSON fields gracefully
        Task t2 = tasks.stream().filter(t -> "task-2".equals(t.getId())).findFirst().orElse(null);
        assertNotNull("task-2 should be present", t2);
        assertEquals(com.leaguesai.data.model.Difficulty.MEDIUM, t2.getDifficulty());
        assertNull(t2.getLocation());
    }

    @Test
    public void testLoadAreas() throws Exception {
        DatabaseLoader loader = new DatabaseLoader(dbFile);
        List<Area> areas = loader.loadAreas();

        assertEquals(1, areas.size());
        Area area = areas.get(0);

        assertEquals("area-misthalin", area.getId());
        assertEquals("Misthalin", area.getName());
        assertEquals(0, area.getUnlockCost());

        assertNotNull(area.getUnlockRequires());
        assertTrue(area.getUnlockRequires().isEmpty());

        assertNotNull(area.getRegionIds());
        assertEquals(3, area.getRegionIds().size());
        assertEquals(Integer.valueOf(12850), area.getRegionIds().get(0));
        assertEquals(Integer.valueOf(12851), area.getRegionIds().get(1));
        assertEquals(Integer.valueOf(12852), area.getRegionIds().get(2));
    }

    @Test
    public void testLoadRelics() throws Exception {
        DatabaseLoader loader = new DatabaseLoader(dbFile);
        List<Relic> relics = loader.loadRelics();

        assertEquals(1, relics.size());
        Relic relic = relics.get(0);

        assertEquals("relic-dragon-slayer", relic.getId());
        assertEquals("Dragon Slayer", relic.getName());
        assertEquals(1, relic.getTier());
        assertEquals("Massively increased damage against dragons.", relic.getDescription());
        assertEquals(500, relic.getUnlockCost());

        assertNotNull(relic.getEffects());
        // Gson parses numbers as Double by default in Map<String,Object>
        Object bonus = relic.getEffects().get("dragon_damage_bonus");
        assertNotNull(bonus);
        assertEquals(5.0, ((Number) bonus).doubleValue(), 0.001);
    }

    @Test
    public void testLoadEmbeddings_emptyWhenNoneStored() throws Exception {
        DatabaseLoader loader = new DatabaseLoader(dbFile);
        Map<String, float[]> embeddings = loader.loadEmbeddings();

        assertNotNull(embeddings);
        assertTrue("No embeddings inserted, should be empty", embeddings.isEmpty());
    }

    @Test
    public void testLoadEmbeddings_loadsCorrectVector() throws Exception {
        // Insert a task with an embedding
        float[] vector = {0.1f, 0.2f, 0.3f, -0.5f};
        byte[] blob = floatsToBytes(vector);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE tasks SET embedding = ? WHERE id = 'task-1'")) {
            ps.setBytes(1, blob);
            ps.executeUpdate();
        }

        DatabaseLoader loader = new DatabaseLoader(dbFile);
        Map<String, float[]> embeddings = loader.loadEmbeddings();

        assertEquals(1, embeddings.size());
        assertTrue(embeddings.containsKey("task-1"));
        float[] loaded = embeddings.get("task-1");
        assertEquals(vector.length, loaded.length);
        for (int i = 0; i < vector.length; i++) {
            assertEquals("Index " + i, vector[i], loaded[i], 1e-6f);
        }
    }

    @Test
    public void testMissingDbFile() {
        File nonExistent = new File(tmpFolder.getRoot(), "does_not_exist.db");
        assertFalse(nonExistent.exists());

        DatabaseLoader loader = new DatabaseLoader(nonExistent);

        List<Task> tasks = loader.loadTasks();
        assertNotNull(tasks);
        assertTrue(tasks.isEmpty());

        List<Area> areas = loader.loadAreas();
        assertNotNull(areas);
        assertTrue(areas.isEmpty());

        List<Relic> relics = loader.loadRelics();
        assertNotNull(relics);
        assertTrue(relics.isEmpty());

        Map<String, float[]> embeddings = loader.loadEmbeddings();
        assertNotNull(embeddings);
        assertTrue(embeddings.isEmpty());
    }

    // Helper: serialize float[] as little-endian bytes (same as what DatabaseLoader expects)
    private static byte[] floatsToBytes(float[] floats) {
        ByteBuffer bb = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            bb.putFloat(f);
        }
        return bb.array();
    }
}
