package com.leaguesai.data;

import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import com.leaguesai.scraper.SqliteWriter;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
}
