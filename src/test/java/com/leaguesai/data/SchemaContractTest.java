package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;
import com.leaguesai.scraper.SqliteWriter;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Round-trip contract test: a task written by the scraper's {@link SqliteWriter}
 * must be loadable by the plugin's {@link DatabaseLoader} with all fields intact.
 *
 * <p>This test exists in the main project (rather than the scraper module) so it
 * can exercise both classes against the same database file. It is the canary
 * for schema drift between the two — if it fails in CI, do NOT ship the
 * scraper output to production.
 */
public class SchemaContractTest {

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @Test
    public void writerLoaderRoundTrip() throws Exception {
        File dbFile = tmpFolder.newFile("contract.db");
        // newFile creates an empty file; SqliteWriter needs to open it as a DB.
        // SQLite is fine opening an empty file.

        SqliteWriter writer = new SqliteWriter(dbFile);
        writer.initialize();

        Map<String, Integer> skills = new LinkedHashMap<>();
        skills.put("Attack", 40);
        skills.put("Strength", 30);
        String skillsJson = SqliteWriter.stringIntMapToJson(skills);

        String locationJson = SqliteWriter.locationToJson(new int[]{3222, 3218, 0});

        // Build an embedding blob (little-endian floats) and verify it round-trips.
        float[] vector = {0.1f, -0.2f, 0.3f, 0.4f};
        ByteBuffer bb = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) bb.putFloat(v);
        byte[] embedding = bb.array();

        writer.upsertTask(
                "Kill a Goblin",
                "Defeat a goblin in combat",
                "easy",
                10,
                "misthalin",
                "combat",
                skillsJson,
                "[\"Cook's Assistant\"]",
                "[]",
                "{\"Bronze sword\":1}",
                locationJson,
                "[{\"id\":2,\"name\":\"Goblin\"}]",
                "[]",
                "[]",
                "https://oldschool.runescape.wiki/w/Goblin",
                embedding
        );
        writer.close();

        // Now load with the plugin's loader and check every field.
        DatabaseLoader loader = new DatabaseLoader(dbFile);
        List<Task> tasks = loader.loadTasks();
        assertEquals("scraper-written task should be loadable", 1, tasks.size());

        Task t = tasks.get(0);
        assertEquals("Kill a Goblin", t.getName());
        assertEquals("Defeat a goblin in combat", t.getDescription());
        assertEquals(Difficulty.EASY, t.getDifficulty());
        assertEquals(10, t.getPoints());
        assertEquals("misthalin", t.getArea());
        assertEquals("combat", t.getCategory());
        assertEquals("https://oldschool.runescape.wiki/w/Goblin", t.getWikiUrl());

        assertNotNull(t.getSkillsRequired());
        assertEquals(Integer.valueOf(40), t.getSkillsRequired().get("Attack"));
        assertEquals(Integer.valueOf(30), t.getSkillsRequired().get("Strength"));

        assertNotNull(t.getQuestsRequired());
        assertEquals(1, t.getQuestsRequired().size());
        assertEquals("Cook's Assistant", t.getQuestsRequired().get(0));

        assertNotNull(t.getTasksRequired());
        assertTrue(t.getTasksRequired().isEmpty());

        assertNotNull(t.getItemsRequired());
        assertEquals(Integer.valueOf(1), t.getItemsRequired().get("Bronze sword"));

        WorldPoint loc = t.getLocation();
        assertNotNull("location should round-trip from JSON to WorldPoint", loc);
        assertEquals(3222, loc.getX());
        assertEquals(3218, loc.getY());
        assertEquals(0, loc.getPlane());

        assertNotNull(t.getTargetNpcs());
        assertEquals(1, t.getTargetNpcs().size());
        assertEquals(2, t.getTargetNpcs().get(0).getId());
        assertEquals("Goblin", t.getTargetNpcs().get(0).getName());

        assertNotNull(t.getTargetObjects());
        assertTrue(t.getTargetObjects().isEmpty());

        assertNotNull(t.getTargetItems());
        assertTrue(t.getTargetItems().isEmpty());

        // Embedding round-trip via the dedicated loadEmbeddings() path.
        Map<String, float[]> embeddings = loader.loadEmbeddings();
        assertEquals(1, embeddings.size());
        float[] loaded = embeddings.values().iterator().next();
        assertEquals(vector.length, loaded.length);
        for (int i = 0; i < vector.length; i++) {
            assertEquals("vector[" + i + "]", vector[i], loaded[i], 1e-6f);
        }
    }

    /**
     * Write 5 tasks across all difficulty tiers and verify every one round-trips.
     */
    @Test
    public void multipleTasksAcrossDifficultiesRoundTrip() throws Exception {
        File dbFile = tmpFolder.newFile("multi.db");
        SqliteWriter writer = new SqliteWriter(dbFile);
        writer.initialize();

        String[] difficulties = {"easy", "medium", "hard", "elite", "master"};
        int[] points = {10, 20, 40, 80, 250};
        for (int i = 0; i < difficulties.length; i++) {
            writer.upsertTask(
                    "Task " + i,
                    "Description " + i,
                    difficulties[i],
                    points[i],
                    "area-" + i,
                    "category-" + i,
                    "{}",
                    "[]",
                    "[]",
                    "{}",
                    null,
                    "[]",
                    "[]",
                    "[]",
                    "https://wiki/task" + i,
                    null
            );
        }
        writer.close();

        DatabaseLoader loader = new DatabaseLoader(dbFile);
        List<Task> tasks = loader.loadTasks();
        assertEquals(5, tasks.size());

        // Map by name for stable assertions regardless of row order.
        Map<String, Task> byName = new LinkedHashMap<>();
        for (Task t : tasks) byName.put(t.getName(), t);

        Difficulty[] expected = {
                Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD,
                Difficulty.ELITE, Difficulty.MASTER
        };
        for (int i = 0; i < expected.length; i++) {
            Task t = byName.get("Task " + i);
            assertNotNull("Task " + i + " missing after round-trip", t);
            assertEquals(expected[i], t.getDifficulty());
            assertEquals(points[i], t.getPoints());
            assertEquals("area-" + i, t.getArea());
            assertEquals("category-" + i, t.getCategory());
        }
    }

    /**
     * A task whose JSON-shaped fields are NULL must load without throwing
     * NullPointerException. The loader returns null collections for absent JSON,
     * which is the contract callers depend on.
     */
    @Test
    public void taskWithNullJsonFieldsLoadsWithoutNpe() throws Exception {
        File dbFile = tmpFolder.newFile("nullfields.db");
        SqliteWriter writer = new SqliteWriter(dbFile);
        writer.initialize();

        writer.upsertTask(
                "Sparse Task",
                "Has no targets at all",
                "easy",
                5,
                "void",
                "misc",
                null,   // skills_required
                null,   // quests_required
                null,   // tasks_required
                null,   // items_required
                null,   // location
                null,   // target_npcs
                null,   // target_objects
                null,   // target_items
                "https://wiki/sparse",
                null    // embedding
        );
        writer.close();

        DatabaseLoader loader = new DatabaseLoader(dbFile);
        List<Task> tasks = loader.loadTasks();
        assertEquals(1, tasks.size());

        Task t = tasks.get(0);
        // Loader returns null for absent JSON — caller code must handle that.
        assertNull(t.getSkillsRequired());
        assertNull(t.getQuestsRequired());
        assertNull(t.getTasksRequired());
        assertNull(t.getItemsRequired());
        assertNull(t.getLocation());
        assertNull(t.getTargetNpcs());
        assertNull(t.getTargetObjects());
        assertNull(t.getTargetItems());

        // And no embeddings should be loaded since the column was null.
        assertTrue(loader.loadEmbeddings().isEmpty());
    }

    /**
     * Areas are not currently writable via {@link SqliteWriter}, but the schema
     * is created on init() and {@link DatabaseLoader#loadAreas()} reads from it.
     * Insert directly via JDBC and verify the loader parses every column.
     */
    @Test
    public void areasRoundTrip() throws Exception {
        File dbFile = tmpFolder.newFile("areas.db");
        SqliteWriter writer = new SqliteWriter(dbFile);
        writer.initialize();
        writer.close();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO areas (id, name, unlock_cost, unlock_requires, region_ids) VALUES (?,?,?,?,?)")) {
            ps.setString(1, "misthalin");
            ps.setString(2, "Misthalin");
            ps.setInt(3, 0);
            ps.setString(4, "[]");
            ps.setString(5, "[12342,12343]");
            ps.executeUpdate();

            ps.setString(1, "kandarin");
            ps.setString(2, "Kandarin");
            ps.setInt(3, 50);
            ps.setString(4, "[\"misthalin\"]");
            ps.setString(5, "[11573]");
            ps.executeUpdate();
        }

        DatabaseLoader loader = new DatabaseLoader(dbFile);
        List<Area> areas = loader.loadAreas();
        assertEquals(2, areas.size());

        Map<String, Area> byId = new LinkedHashMap<>();
        for (Area a : areas) byId.put(a.getId(), a);

        Area mist = byId.get("misthalin");
        assertNotNull(mist);
        assertEquals("Misthalin", mist.getName());
        assertEquals(0, mist.getUnlockCost());
        assertNotNull(mist.getRegionIds());
        assertEquals(2, mist.getRegionIds().size());
        assertEquals(Integer.valueOf(12342), mist.getRegionIds().get(0));

        Area kan = byId.get("kandarin");
        assertNotNull(kan);
        assertEquals(50, kan.getUnlockCost());
        assertEquals(1, kan.getUnlockRequires().size());
        assertEquals("misthalin", kan.getUnlockRequires().get(0));
    }

    /**
     * Same direct-JDBC pattern for relics — schema created by SqliteWriter,
     * row inserted via JDBC, loader verifies every column.
     */
    @Test
    public void relicsRoundTrip() throws Exception {
        File dbFile = tmpFolder.newFile("relics.db");
        SqliteWriter writer = new SqliteWriter(dbFile);
        writer.initialize();
        writer.close();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO relics (id, name, tier, description, unlock_cost, effects) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, "endless-harvest");
            ps.setString(2, "Endless Harvest");
            ps.setInt(3, 1);
            ps.setString(4, "Resources never deplete");
            ps.setInt(5, 500);
            ps.setString(6, "{\"resource_depletion\":false,\"yield_multiplier\":2}");
            ps.executeUpdate();
        }

        DatabaseLoader loader = new DatabaseLoader(dbFile);
        List<Relic> relics = loader.loadRelics();
        assertEquals(1, relics.size());
        Relic r = relics.get(0);
        assertEquals("endless-harvest", r.getId());
        assertEquals("Endless Harvest", r.getName());
        assertEquals(1, r.getTier());
        assertEquals("Resources never deplete", r.getDescription());
        assertEquals(500, r.getUnlockCost());
        assertNotNull(r.getEffects());
        assertEquals(Boolean.FALSE, r.getEffects().get("resource_depletion"));
    }

    /**
     * Embedding bytes-in == bytes-out for a larger vector. Pins the
     * little-endian IEEE-754 contract between scraper and loader.
     */
    @Test
    public void embeddingBlobByteForByteRoundTrip() throws Exception {
        File dbFile = tmpFolder.newFile("embed.db");
        SqliteWriter writer = new SqliteWriter(dbFile);
        writer.initialize();

        float[] vector = new float[1536];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) Math.sin(i * 0.01);
        }
        ByteBuffer bb = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) bb.putFloat(v);
        byte[] originalBytes = bb.array();

        writer.upsertTask(
                "Embedded Task",
                "Has a 1536-dim embedding",
                "easy",
                1,
                "anywhere",
                "misc",
                "{}", "[]", "[]", "{}",
                null, "[]", "[]", "[]",
                "https://wiki/embedded",
                originalBytes
        );
        writer.close();

        DatabaseLoader loader = new DatabaseLoader(dbFile);
        Map<String, float[]> embeddings = loader.loadEmbeddings();
        assertEquals(1, embeddings.size());
        float[] loaded = embeddings.values().iterator().next();
        assertEquals(vector.length, loaded.length);
        for (int i = 0; i < vector.length; i++) {
            assertEquals("vector[" + i + "]", vector[i], loaded[i], 0.0f);
        }
    }
}
