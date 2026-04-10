package com.leaguesai.data;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.io.*;
import java.nio.file.*;
import java.sql.DriverManager;

/**
 * On first startup, seeds {@code ~/.runelite/leagues-ai/data/leagues-vi-tasks.db}
 * from the bundled classpath resource if the file does not already exist.
 *
 * <p>Two-path approach:
 * <ol>
 *   <li>If {@code /leagues-vi-tasks.db} exists on the classpath (compiled into the jar
 *       on launch day), copy it to the target path.</li>
 *   <li>Otherwise, create an empty but valid SQLite database at the target path using
 *       sqlite-jdbc so that {@link DatabaseLoader} doesn't crash on missing file.</li>
 * </ol>
 *
 * <p>Friends who install the plugin before running the scraper get a working (empty)
 * database immediately. On launch day the jar will carry a real snapshot.
 */
@Slf4j
@Singleton
public class DatabaseSeeder {

    private static final String CLASSPATH_DB = "/leagues-vi-tasks.db";

    /**
     * Seeds the database if absent. No-op if {@code targetFile} already exists.
     *
     * @param targetFile path to seed into (typically ~/.runelite/leagues-ai/data/leagues-vi-tasks.db)
     */
    public void seedIfAbsent(File targetFile) {
        if (targetFile == null) return;
        if (targetFile.exists()) {
            log.debug("DatabaseSeeder: DB already exists at {}, skipping seed", targetFile.getAbsolutePath());
            return;
        }

        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        // Path 1: copy bundled classpath resource (launch-day jar will have a real snapshot)
        try (InputStream in = DatabaseSeeder.class.getResourceAsStream(CLASSPATH_DB)) {
            if (in != null) {
                Files.copy(in, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("DatabaseSeeder: seeded DB from bundled snapshot -> {}", targetFile.getAbsolutePath());
                return;
            }
        } catch (IOException e) {
            log.warn("DatabaseSeeder: failed to copy bundled DB: {}", e.getMessage());
            // Fall through to path 2
        }

        // Path 2: no classpath resource — create an empty valid SQLite DB via sqlite-jdbc.
        // A PRAGMA read forces sqlite-jdbc to flush the 100-byte SQLite header to disk.
        try {
            String url = "jdbc:sqlite:" + targetFile.getAbsolutePath();
            try (java.sql.Connection conn = DriverManager.getConnection(url);
                 java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA user_version = 0");
                log.info("DatabaseSeeder: created empty DB at {}", targetFile.getAbsolutePath());
            }
        } catch (java.sql.SQLException e) {
            log.warn("DatabaseSeeder: could not create empty DB: {}", e.getMessage());
            // Non-fatal — plugin continues without a seeded DB
        }
    }
}
