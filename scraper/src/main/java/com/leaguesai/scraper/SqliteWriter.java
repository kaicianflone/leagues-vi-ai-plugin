package com.leaguesai.scraper;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Handles all SQLite I/O for the wiki scraper.
 *
 * <p>The schema mirrors what {@code com.leaguesai.data.DatabaseLoader} in the
 * plugin expects so that the database produced by the scraper can be dropped
 * straight into {@code ~/.runelite/leagues-ai/data/}.
 *
 * <p>Column shapes:
 * <ul>
 *   <li>{@code location} is a JSON string {@code {"x":N,"y":N,"plane":N}}</li>
 *   <li>{@code skills_required} is a JSON string {@code {"Skill":level}}</li>
 *   <li>{@code embedding} is a BLOB of little-endian IEEE-754 floats</li>
 * </ul>
 */
public class SqliteWriter {

    private final File dbFile;
    private Connection connection;

    /**
     * @param dbFile path to the SQLite file; parent directories must already exist
     */
    public SqliteWriter(File dbFile) {
        this.dbFile = dbFile;
    }

    /**
     * Opens (or creates) the database and creates all required tables if they
     * do not already exist. Schema must match what {@code DatabaseLoader}
     * reads, otherwise no rows will be loaded in production.
     *
     * @throws SQLException on any database error
     */
    public void initialize() throws SQLException {
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        connection = DriverManager.getConnection(url);

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS areas (" +
                "  id              TEXT PRIMARY KEY," +
                "  name            TEXT NOT NULL," +
                "  unlock_cost     INTEGER NOT NULL DEFAULT 0," +
                "  unlock_requires TEXT," +
                "  region_ids      TEXT" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS relics (" +
                "  id          TEXT PRIMARY KEY," +
                "  name        TEXT NOT NULL," +
                "  tier        INTEGER NOT NULL DEFAULT 0," +
                "  description TEXT," +
                "  unlock_cost INTEGER NOT NULL DEFAULT 0," +
                "  effects     TEXT" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS tasks (" +
                "  id              TEXT PRIMARY KEY," +
                "  name            TEXT NOT NULL," +
                "  description     TEXT," +
                "  difficulty      TEXT NOT NULL DEFAULT 'easy'," +
                "  points          INTEGER NOT NULL DEFAULT 0," +
                "  area            TEXT," +
                "  category        TEXT," +
                "  skills_required TEXT," +
                "  quests_required TEXT," +
                "  tasks_required  TEXT," +
                "  items_required  TEXT," +
                "  location        TEXT," +
                "  target_npcs     TEXT," +
                "  target_objects  TEXT," +
                "  target_items    TEXT," +
                "  wiki_url        TEXT," +
                "  embedding       BLOB" +
                ")"
            );
        }
    }

    /**
     * Inserts or replaces a task row. All JSON-shaped string fields are
     * passed through verbatim — the caller is responsible for producing
     * valid JSON (or null/empty).
     *
     * @param name           task name (used to derive a deterministic UUID)
     * @param description    full task description text
     * @param difficulty     normalized difficulty string ("easy", "medium", ...)
     * @param points         league point value
     * @param area           area / region name
     * @param category       task category (combat, skilling, etc.)
     * @param skillsRequired JSON object string {@code {"Attack":1}} or null
     * @param questsRequired JSON array string {@code ["Cook's Assistant"]} or null
     * @param tasksRequired  JSON array string {@code ["task-id"]} or null
     * @param itemsRequired  JSON object string {@code {"Bronze sword":1}} or null
     * @param location       JSON object string {@code {"x":N,"y":N,"plane":N}} or null
     * @param targetNpcs     JSON array string {@code [{"id":N,"name":"..."}]} or null
     * @param targetObjects  JSON array string {@code [{"id":N,"name":"..."}]} or null
     * @param targetItems    JSON array string {@code [{"id":N,"name":"..."}]} or null
     * @param wikiUrl        canonical wiki URL
     * @param embedding      serialized float vector bytes (little-endian), or null
     * @throws SQLException on any database error
     */
    public void upsertTask(
            String name,
            String description,
            String difficulty,
            int points,
            String area,
            String category,
            String skillsRequired,
            String questsRequired,
            String tasksRequired,
            String itemsRequired,
            String location,
            String targetNpcs,
            String targetObjects,
            String targetItems,
            String wikiUrl,
            byte[] embedding
    ) throws SQLException {
        String id = UUID.nameUUIDFromBytes(name.getBytes()).toString();
        upsertTaskWithId(id, name, description, difficulty, points, area, category,
                skillsRequired, questsRequired, tasksRequired, itemsRequired,
                location, targetNpcs, targetObjects, targetItems, wikiUrl, embedding);
    }

    /**
     * Upsert variant that takes an explicit ID (e.g. wiki data-taskid). Use this
     * when you have a stable upstream identifier instead of hashing the name.
     */
    public void upsertTaskWithId(
            String id,
            String name,
            String description,
            String difficulty,
            int points,
            String area,
            String category,
            String skillsRequired,
            String questsRequired,
            String tasksRequired,
            String itemsRequired,
            String location,
            String targetNpcs,
            String targetObjects,
            String targetItems,
            String wikiUrl,
            byte[] embedding
    ) throws SQLException {
        String sql =
            "INSERT OR REPLACE INTO tasks " +
            "(id, name, description, difficulty, points, area, category, " +
            " skills_required, quests_required, tasks_required, items_required, " +
            " location, target_npcs, target_objects, target_items, wiki_url, embedding) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setString(4, difficulty != null ? difficulty : "easy");
            ps.setInt(5, points);
            ps.setString(6, area);
            ps.setString(7, category);
            ps.setString(8, skillsRequired);
            ps.setString(9, questsRequired);
            ps.setString(10, tasksRequired);
            ps.setString(11, itemsRequired);
            ps.setString(12, location);
            ps.setString(13, targetNpcs);
            ps.setString(14, targetObjects);
            ps.setString(15, targetItems);
            ps.setString(16, wikiUrl);
            if (embedding != null) {
                ps.setBytes(17, embedding);
            } else {
                ps.setNull(17, java.sql.Types.BLOB);
            }
            ps.executeUpdate();
        }
    }

    /** Closes the underlying database connection. */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Build a JSON object string from a {@code Map<String,Integer>} suitable
     * for the {@code skills_required} or {@code items_required} columns.
     */
    public static String stringIntMapToJson(java.util.Map<String, Integer> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> entry : map.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escape(entry.getKey())).append('"')
              .append(':').append(entry.getValue());
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Build the {@code location} JSON string from raw tile coordinates, or
     * null if no location.
     */
    public static String locationToJson(int[] location) {
        if (location == null || location.length != 3) {
            return null;
        }
        return "{\"x\":" + location[0] + ",\"y\":" + location[1]
                + ",\"plane\":" + location[2] + "}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
