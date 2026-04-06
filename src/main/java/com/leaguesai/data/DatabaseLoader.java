package com.leaguesai.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;
import net.runelite.api.coords.WorldPoint;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads task, area, relic data and embeddings from a SQLite .db file.
 * If the db file does not exist, all load methods return empty collections.
 */
public class DatabaseLoader {

    private static final Gson GSON = new Gson();

    private final File dbFile;

    public DatabaseLoader(File dbFile) {
        this.dbFile = dbFile;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Load all rows from the {@code tasks} table. */
    public List<Task> loadTasks() {
        if (!dbFile.exists()) {
            return Collections.emptyList();
        }
        List<Task> tasks = new ArrayList<>();
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM tasks")) {

            while (rs.next()) {
                tasks.add(parseTask(rs));
            }
        } catch (Exception e) {
            // Return whatever was collected; log silently.
        }
        return tasks;
    }

    /** Load all rows from the {@code areas} table. */
    public List<Area> loadAreas() {
        if (!dbFile.exists()) {
            return Collections.emptyList();
        }
        List<Area> areas = new ArrayList<>();
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM areas")) {

            while (rs.next()) {
                areas.add(parseArea(rs));
            }
        } catch (Exception e) {
            // Return whatever was collected.
        }
        return areas;
    }

    /** Load all rows from the {@code relics} table. */
    public List<Relic> loadRelics() {
        if (!dbFile.exists()) {
            return Collections.emptyList();
        }
        List<Relic> relics = new ArrayList<>();
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM relics")) {

            while (rs.next()) {
                relics.add(parseRelic(rs));
            }
        } catch (Exception e) {
            // Return whatever was collected.
        }
        return relics;
    }

    /**
     * Load task ID → embedding vector from the {@code tasks} table where
     * {@code embedding IS NOT NULL}.  Embeddings are stored as BLOB containing
     * little-endian IEEE-754 floats.
     */
    public Map<String, float[]> loadEmbeddings() {
        if (!dbFile.exists()) {
            return Collections.emptyMap();
        }
        Map<String, float[]> result = new LinkedHashMap<>();
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, embedding FROM tasks WHERE embedding IS NOT NULL")) {

            while (rs.next()) {
                String id = rs.getString("id");
                byte[] blob = rs.getBytes("embedding");
                if (blob != null && blob.length > 0 && blob.length % 4 == 0) {
                    result.put(id, bytesToFloats(blob));
                }
            }
        } catch (Exception e) {
            // Return whatever was collected.
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Parsers
    // -------------------------------------------------------------------------

    private Task parseTask(ResultSet rs) throws Exception {
        String difficultyStr = rs.getString("difficulty");
        Difficulty difficulty = null;
        if (difficultyStr != null) {
            difficulty = Difficulty.fromString(difficultyStr);
        }

        return Task.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .wikiUrl(rs.getString("wiki_url"))
                .difficulty(difficulty)
                .points(rs.getInt("points"))
                .area(rs.getString("area"))
                .category(rs.getString("category"))
                .skillsRequired(parseStringIntMap(rs.getString("skills_required")))
                .questsRequired(parseStringList(rs.getString("quests_required")))
                .tasksRequired(parseStringList(rs.getString("tasks_required")))
                .itemsRequired(parseStringIntMap(rs.getString("items_required")))
                .location(parseWorldPoint(rs.getString("location")))
                .targetNpcs(parseNpcTargets(rs.getString("target_npcs")))
                .targetObjects(parseObjectTargets(rs.getString("target_objects")))
                .targetItems(parseItemTargets(rs.getString("target_items")))
                .build();
    }

    private Area parseArea(ResultSet rs) throws Exception {
        return Area.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .unlockCost(rs.getInt("unlock_cost"))
                .unlockRequires(parseStringList(rs.getString("unlock_requires")))
                .regionIds(parseIntList(rs.getString("region_ids")))
                .build();
    }

    private Relic parseRelic(ResultSet rs) throws Exception {
        return Relic.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .tier(rs.getInt("tier"))
                .description(rs.getString("description"))
                .unlockCost(rs.getInt("unlock_cost"))
                .effects(parseStringObjectMap(rs.getString("effects")))
                .build();
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    /** Parse a JSON object like {@code {"Attack":1,"Strength":5}} into Map<String,Integer>. */
    private Map<String, Integer> parseStringIntMap(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Type type = new TypeToken<Map<String, Integer>>() {}.getType();
        return GSON.fromJson(json, type);
    }

    /** Parse a JSON object like {@code {"key":"value"}} into Map<String,Object>. */
    private Map<String, Object> parseStringObjectMap(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        return GSON.fromJson(json, type);
    }

    /** Parse a JSON string array into List<String>. */
    private List<String> parseStringList(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Type type = new TypeToken<List<String>>() {}.getType();
        return GSON.fromJson(json, type);
    }

    /** Parse a JSON integer array into List<Integer>. */
    private List<Integer> parseIntList(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Type type = new TypeToken<List<Integer>>() {}.getType();
        return GSON.fromJson(json, type);
    }

    /**
     * Parse location JSON {@code {"x":int,"y":int,"plane":int}} into a {@link WorldPoint}.
     * Returns {@code null} if the JSON is absent.
     */
    private WorldPoint parseWorldPoint(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        JsonObject obj = GSON.fromJson(json, JsonObject.class);
        int x = obj.get("x").getAsInt();
        int y = obj.get("y").getAsInt();
        int plane = obj.get("plane").getAsInt();
        return new WorldPoint(x, y, plane);
    }

    /** Parse a JSON array of {@code {"id":int,"name":str}} into NpcTarget list. */
    private List<Task.NpcTarget> parseNpcTargets(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        List<Task.NpcTarget> list = new ArrayList<>();
        JsonArray arr = GSON.fromJson(json, JsonArray.class);
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            list.add(Task.NpcTarget.builder()
                    .id(obj.get("id").getAsInt())
                    .name(obj.get("name").getAsString())
                    .build());
        }
        return list;
    }

    /** Parse a JSON array of {@code {"id":int,"name":str}} into ObjectTarget list. */
    private List<Task.ObjectTarget> parseObjectTargets(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        List<Task.ObjectTarget> list = new ArrayList<>();
        JsonArray arr = GSON.fromJson(json, JsonArray.class);
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            list.add(Task.ObjectTarget.builder()
                    .id(obj.get("id").getAsInt())
                    .name(obj.get("name").getAsString())
                    .build());
        }
        return list;
    }

    /** Parse a JSON array of {@code {"id":int,"name":str}} into ItemTarget list. */
    private List<Task.ItemTarget> parseItemTargets(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        List<Task.ItemTarget> list = new ArrayList<>();
        JsonArray arr = GSON.fromJson(json, JsonArray.class);
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            list.add(Task.ItemTarget.builder()
                    .id(obj.get("id").getAsInt())
                    .name(obj.get("name").getAsString())
                    .build());
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Embedding helpers
    // -------------------------------------------------------------------------

    /** Deserialize little-endian float bytes into a float[]. */
    private static float[] bytesToFloats(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = bb.getFloat();
        }
        return floats;
    }

    // -------------------------------------------------------------------------
    // Connection helper
    // -------------------------------------------------------------------------

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }
}
