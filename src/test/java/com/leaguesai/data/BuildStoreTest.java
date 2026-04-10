package com.leaguesai.data;

import com.google.gson.Gson;
import com.leaguesai.data.model.Build;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Coverage for {@link BuildStore}: seed loading, save/delete, import/export,
 * and atomic-write safety.
 */
public class BuildStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Gson GSON = new Gson();

    // -------------------------------------------------------------------------
    // Helper: create a minimal Build
    // -------------------------------------------------------------------------

    private Build buildWith(String id, String name) {
        return Build.builder()
                .id(id)
                .name(name)
                .description("desc")
                .author("test")
                .version(1)
                .build();
    }

    /** Write a {"builds":[...]} file to a temp location. */
    private File writeBuildFile(List<Build> builds) throws Exception {
        File f = tmp.newFile();
        String json = "{\"builds\":" + GSON.toJson(builds) + "}";
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    // -------------------------------------------------------------------------
    // 1. seeds_load_from_classpath
    // -------------------------------------------------------------------------

    @Test
    public void seeds_load_from_classpath() {
        BuildStore store = new BuildStore(new File(tmp.getRoot(), "builds.json"));
        assertEquals("Expected 5 seed builds from /builds.json", 5, store.listSeeds().size());
    }

    // -------------------------------------------------------------------------
    // 2. saved_builds_empty_by_default
    // -------------------------------------------------------------------------

    @Test
    public void saved_builds_empty_by_default() {
        // Point at a file that doesn't exist yet.
        BuildStore store = new BuildStore(new File(tmp.getRoot(), "nonexistent/builds.json"));
        assertTrue("listSaved() should be empty when no saves file exists",
                store.listSaved().isEmpty());
    }

    // -------------------------------------------------------------------------
    // 3. save_and_list
    // -------------------------------------------------------------------------

    @Test
    public void save_and_list() {
        File f = new File(tmp.getRoot(), "builds.json");
        BuildStore store = new BuildStore(f);

        Build b = buildWith("my_custom_v1", "My Custom");
        store.save(b);

        assertEquals(1, store.listSaved().size());
        assertEquals("my_custom_v1", store.listSaved().get(0).getId());

        // listAll should have seeds + 1 saved
        assertTrue("listAll must include seeds + saved", store.listAll().size() >= 6);
    }

    // -------------------------------------------------------------------------
    // 4. saved_wins_over_seed_in_listAll
    // -------------------------------------------------------------------------

    @Test
    public void saved_wins_over_seed_in_listAll() {
        File f = new File(tmp.getRoot(), "builds.json");
        BuildStore store = new BuildStore(f);

        // "melee_bosser_v1" is a seed id — override it
        Build override = buildWith("melee_bosser_v1", "Custom Melee Override");
        store.save(override);

        List<Build> all = store.listAll();
        // Still 5 total (seed replaced, not duplicated)
        assertEquals(5, all.size());

        // The returned version should be the saved override
        Build found = all.stream()
                .filter(b -> "melee_bosser_v1".equals(b.getId()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("Custom Melee Override", found.getName());
    }

    // -------------------------------------------------------------------------
    // 5. delete_removes_from_saved
    // -------------------------------------------------------------------------

    @Test
    public void delete_removes_from_saved() {
        File f = new File(tmp.getRoot(), "builds.json");
        BuildStore store = new BuildStore(f);

        store.save(buildWith("my_custom_v1", "My Custom"));
        assertEquals(1, store.listSaved().size());

        store.delete("my_custom_v1");
        assertTrue(store.listSaved().isEmpty());

        // Reload — should still be empty
        BuildStore reloaded = new BuildStore(f);
        assertTrue(reloaded.listSaved().isEmpty());
    }

    // -------------------------------------------------------------------------
    // 6. delete_does_not_affect_seeds
    // -------------------------------------------------------------------------

    @Test
    public void delete_does_not_affect_seeds() {
        BuildStore store = new BuildStore(new File(tmp.getRoot(), "builds.json"));
        int seedCount = store.listSeeds().size();

        // Attempting to delete a seed id should not change listSeeds()
        store.delete("melee_bosser_v1");
        assertEquals("Seeds must be unaffected by delete", seedCount, store.listSeeds().size());
    }

    // -------------------------------------------------------------------------
    // 7. import_valid_single_build_file
    // -------------------------------------------------------------------------

    @Test
    public void import_valid_single_build_file() throws Exception {
        File f = new File(tmp.getRoot(), "builds.json");
        BuildStore store = new BuildStore(f);

        List<Build> toImport = new ArrayList<>();
        toImport.add(buildWith("imported_v1", "Imported Build"));
        File importFile = writeBuildFile(toImport);

        Build result = store.importFromFile(importFile);

        assertNotNull(result);
        assertEquals("imported_v1", result.getId());
        assertEquals(1, store.listSaved().size());
        assertEquals("imported_v1", store.listSaved().get(0).getId());
    }

    // -------------------------------------------------------------------------
    // 8. import_rejects_malformed_json
    // -------------------------------------------------------------------------

    @Test
    public void import_rejects_malformed_json() throws Exception {
        File f = new File(tmp.getRoot(), "builds.json");
        BuildStore store = new BuildStore(f);

        File badFile = tmp.newFile();
        Files.write(badFile.toPath(), "{{{ garbage !!!".getBytes(StandardCharsets.UTF_8));

        try {
            store.importFromFile(badFile);
            fail("Expected IllegalArgumentException for malformed JSON");
        } catch (IllegalArgumentException e) {
            assertTrue("Message must mention malformed JSON: " + e.getMessage(),
                    e.getMessage().contains("malformed JSON"));
        }
    }

    // -------------------------------------------------------------------------
    // 9. import_rejects_too_many_builds
    // -------------------------------------------------------------------------

    @Test
    public void import_rejects_too_many_builds() throws Exception {
        File f = new File(tmp.getRoot(), "builds.json");
        BuildStore store = new BuildStore(f);

        List<Build> tooMany = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tooMany.add(buildWith("build-" + i, "Build " + i));
        }
        File importFile = writeBuildFile(tooMany);

        try {
            store.importFromFile(importFile);
            fail("Expected IllegalArgumentException for too many builds");
        } catch (IllegalArgumentException e) {
            assertTrue("Message must mention max 5: " + e.getMessage(),
                    e.getMessage().contains("max 5"));
        }
    }

    // -------------------------------------------------------------------------
    // 10. import_rejects_invalid_id
    // -------------------------------------------------------------------------

    @Test
    public void import_rejects_invalid_id() throws Exception {
        File f = new File(tmp.getRoot(), "builds.json");
        BuildStore store = new BuildStore(f);

        // Build an import file with a path-traversal id that must be rejected.
        File badIdFile = tmp.newFile();
        String badJson = "{\"builds\":[{\"id\":\"../../etc/passwd\",\"name\":\"evil\",\"version\":1}]}";
        Files.write(badIdFile.toPath(), badJson.getBytes(StandardCharsets.UTF_8));

        try {
            store.importFromFile(badIdFile);
            fail("Expected IllegalArgumentException for invalid id");
        } catch (IllegalArgumentException e) {
            assertTrue("Message must mention invalid characters: " + e.getMessage(),
                    e.getMessage().contains("invalid characters"));
        }
    }

    // -------------------------------------------------------------------------
    // 11. export_then_reimport_roundtrip
    // -------------------------------------------------------------------------

    @Test
    public void export_then_reimport_roundtrip() throws Exception {
        File savesFile = new File(tmp.getRoot(), "builds.json");
        BuildStore store = new BuildStore(savesFile);

        Build original = buildWith("roundtrip_v1", "Roundtrip Build");
        File exportFile = tmp.newFile();
        store.exportToFile(original, exportFile);

        // Confirm file exists and has content
        assertTrue(exportFile.exists());
        assertTrue(exportFile.length() > 0);

        // Import back
        Build reimported = store.importFromFile(exportFile);
        assertNotNull(reimported);
        assertEquals(original.getId(), reimported.getId());
        assertEquals(original.getName(), reimported.getName());
        assertEquals(original.getDescription(), reimported.getDescription());
        assertEquals(original.getAuthor(), reimported.getAuthor());
        assertEquals(original.getVersion(), reimported.getVersion());
    }

    // -------------------------------------------------------------------------
    // 12. atomic_save_no_partial_file
    // -------------------------------------------------------------------------

    @Test
    public void atomic_save_no_partial_file() throws Exception {
        // savesFile points inside a non-existent sub-directory — parent.mkdirs()
        // will silently fail if the parent itself is inside a non-writable location.
        // We simulate this by pointing at a path whose parent does not exist and
        // cannot be created (a file used as a directory name component).
        File blocker = tmp.newFile("not-a-dir");  // this is a file, not a dir
        // savesFile would need to live inside "not-a-dir" — impossible
        File impossible = new File(blocker, "builds.json");

        BuildStore store = new BuildStore(impossible);

        // save() must NOT throw even though the path is unwritable.
        store.save(buildWith("safe_build_v1", "Safe Build"));

        // The original blocker file must be untouched (no corruption).
        assertTrue("Blocker file must still exist", blocker.exists());
        assertTrue("Blocker file must still be a regular file", blocker.isFile());
    }
}
