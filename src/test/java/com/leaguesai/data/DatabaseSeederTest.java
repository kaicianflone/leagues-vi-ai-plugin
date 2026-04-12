package com.leaguesai.data;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.Assert.*;

public class DatabaseSeederTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final DatabaseSeeder seeder = new DatabaseSeeder();

    /**
     * When the target file does not exist, seedIfAbsent should create a valid
     * SQLite database (either copied from classpath or created via sqlite-jdbc).
     */
    @Test
    public void seedIfAbsent_creates_db_when_not_exists() throws Exception {
        File dbFile = new File(tempFolder.getRoot(), "new-dir/leagues-vi-tasks.db");
        assertFalse("Precondition: file must not exist before seeding", dbFile.exists());

        seeder.seedIfAbsent(dbFile);

        assertTrue("DB file should exist after seedIfAbsent", dbFile.exists());
        assertTrue("DB file must be non-empty (at least the 100-byte SQLite header)", dbFile.length() > 0);

        // Verify it is a valid SQLite database that we can open
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            assertNotNull("JDBC connection to seeded DB must be non-null", conn);
            assertFalse("Connection must not be closed immediately", conn.isClosed());
        }
    }

    /**
     * When the target file already exists, seedIfAbsent must leave it untouched.
     */
    @Test
    public void seedIfAbsent_noop_when_file_exists() throws Exception {
        File dbFile = tempFolder.newFile("existing.db");
        try (FileWriter fw = new FileWriter(dbFile)) {
            fw.write("existing content");
        }
        long sizeBefore = dbFile.length();

        seeder.seedIfAbsent(dbFile);

        assertEquals("File length must be unchanged", sizeBefore, dbFile.length());

        // Read contents back and confirm unchanged
        byte[] bytes = java.nio.file.Files.readAllBytes(dbFile.toPath());
        String content = new String(bytes);
        assertEquals("File content must be unchanged", "existing content", content);
    }

    /**
     * seedIfAbsent(null) must not throw any exception.
     */
    @Test
    public void seedIfAbsent_null_target_does_not_throw() {
        // Should silently return without any exception
        seeder.seedIfAbsent(null);
    }
}
