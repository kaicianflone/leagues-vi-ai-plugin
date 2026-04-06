package com.leaguesai.scraper;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

/**
 * Handles all SQLite I/O for the wiki scraper.
 *
 * <p>The schema mirrors what {@code DatabaseLoader} in the plugin expects so
 * that the database produced by the scraper can be dropped straight into
 * {@code ~/.runelite/leagues-ai/data/}.
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
     * Opens (or creates) the database and creates all required tables if they do
     * not already exist.
     *
     * @throws SQLException on any database error
     */
    public void initialize() throws SQLException {
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        connection = DriverManager.getConnection(url);

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS areas (" +
                "  id   TEXT PRIMARY KEY," +
                "  name TEXT NOT NULL" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS relics (" +
                "  id     TEXT PRIMARY KEY," +
                "  name   TEXT NOT NULL," +
                "  tier   INTEGER NOT NULL DEFAULT 0," +
                "  area   TEXT" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS tasks (" +
                "  id          TEXT PRIMARY KEY," +
                "  name        TEXT NOT NULL," +
                "  description TEXT," +
                "  difficulty  TEXT NOT NULL DEFAULT 'easy'," +
                "  points      INTEGER NOT NULL DEFAULT 0," +
                "  area        TEXT," +
                "  skills_req  TEXT," +
                "  location_x  INTEGER," +
                "  location_y  INTEGER," +
                "  location_plane INTEGER," +
                "  embedding   BLOB," +
                "  wiki_url    TEXT" +
                ")"
            );
        }
    }

    /**
     * Inserts or replaces a task row.
     *
     * @param name        task name (used to derive a deterministic UUID)
     * @param description full task description text
     * @param difficulty  normalized difficulty string
     * @param points      league point value
     * @param area        area / region name
     * @param skillsReq   parsed skill requirements (may be null)
     * @param location    tile coordinates {@code [x, y, plane]}, or null
     * @param embedding   serialized float vector bytes, or null
     * @param wikiUrl     canonical wiki URL
     * @throws SQLException on any database error
     */
    public void upsertTask(
            String name,
            String description,
            String difficulty,
            int points,
            String area,
            Map<String, Integer> skillsReq,
            int[] location,
            byte[] embedding,
            String wikiUrl
    ) throws SQLException {
        String id = UUID.nameUUIDFromBytes(name.getBytes()).toString();

        String skillsJson = skillsReqToJson(skillsReq);

        Integer locX = null, locY = null, locPlane = null;
        if (location != null && location.length == 3) {
            locX = location[0];
            locY = location[1];
            locPlane = location[2];
        }

        String sql =
            "INSERT OR REPLACE INTO tasks " +
            "(id, name, description, difficulty, points, area, skills_req, " +
            " location_x, location_y, location_plane, embedding, wiki_url) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setString(4, difficulty != null ? difficulty : "easy");
            ps.setInt(5, points);
            ps.setString(6, area);
            ps.setString(7, skillsJson);
            if (locX != null) {
                ps.setInt(8, locX);
                ps.setInt(9, locY);
                ps.setInt(10, locPlane);
            } else {
                ps.setNull(8, java.sql.Types.INTEGER);
                ps.setNull(9, java.sql.Types.INTEGER);
                ps.setNull(10, java.sql.Types.INTEGER);
            }
            if (embedding != null) {
                ps.setBytes(11, embedding);
            } else {
                ps.setNull(11, java.sql.Types.BLOB);
            }
            ps.setString(12, wikiUrl);
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

    private static String skillsReqToJson(Map<String, Integer> skills) {
        if (skills == null || skills.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : skills.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(entry.getKey()).append('"')
              .append(':').append(entry.getValue());
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }
}
