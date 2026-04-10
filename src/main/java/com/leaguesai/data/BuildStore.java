package com.leaguesai.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.leaguesai.data.model.Build;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Manages the build library for Leagues VI AI Plugin.
 *
 * <p>Seed builds come from the classpath resource {@code /builds.json}. User-saved
 * builds are persisted to {@code ~/.runelite/leagues-ai/data/builds.json}.
 *
 * <p>Writes go through a temp-file-and-rename dance (same as {@link GoalStore})
 * so a crash mid-write cannot leave a truncated file on disk.
 */
@Slf4j
@Singleton
public class BuildStore {

    private static final Gson GSON = new Gson();
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_\\-]{1,64}");
    private static final int MAX_IMPORT_BUILDS = 5;
    private static final int MAX_FIELD_LENGTH = 200;

    /** Inner wrapper used when reading / writing the JSON envelope. */
    private static class BuildsWrapper {
        List<Build> builds;
    }

    private final File savesFile;
    private final List<Build> seedBuilds;
    /** id → Build; later entries win (saved overwrites seed when listing all). */
    private final LinkedHashMap<String, Build> savedBuilds;

    public BuildStore(File savesFile) {
        this.savesFile = savesFile;
        this.seedBuilds = loadSeeds();
        this.savedBuilds = loadSaved();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns all builds: seeds + saved, with saved winning on id collision. */
    public synchronized List<Build> listAll() {
        // Start with seeds, then overwrite with any saved build that shares an id.
        LinkedHashMap<String, Build> merged = new LinkedHashMap<>();
        for (Build b : seedBuilds) {
            merged.put(b.getId(), b);
        }
        merged.putAll(savedBuilds);
        return Collections.unmodifiableList(new ArrayList<>(merged.values()));
    }

    /** Returns the seed builds loaded from classpath {@code /builds.json}. */
    public synchronized List<Build> listSeeds() {
        return Collections.unmodifiableList(new ArrayList<>(seedBuilds));
    }

    /** Returns the user-saved builds; empty list if the saves file doesn't exist. */
    public synchronized List<Build> listSaved() {
        return Collections.unmodifiableList(new ArrayList<>(savedBuilds.values()));
    }

    /** Upserts a build by id in the saves file. */
    public synchronized void save(Build build) {
        if (build == null || build.getId() == null) return;
        savedBuilds.put(build.getId(), build);
        persist(savedBuilds.values());
    }

    /**
     * Removes a build from the saves file. Seeds are never deleted — this is a
     * no-op when the id belongs only to a seed build.
     */
    public synchronized void delete(String id) {
        if (id == null || id.isEmpty()) return;
        if (savedBuilds.remove(id) != null) {
            persist(savedBuilds.values());
        }
    }

    /**
     * Validates the given file and merges its builds into the saved map.
     *
     * <p>Validation rules:
     * <ol>
     *   <li>Must be parseable JSON — else {@link IllegalArgumentException}.</li>
     *   <li>Top-level must have a {@code builds} array — else {@link IllegalArgumentException}.</li>
     *   <li>Max {@value #MAX_IMPORT_BUILDS} builds per file — else {@link IllegalArgumentException}.</li>
     *   <li>Each build id must match {@code [a-z0-9_\-]{1,64}} — else {@link IllegalArgumentException}.</li>
     *   <li>String fields (name, description, author) are silently truncated to
     *       {@value #MAX_FIELD_LENGTH} chars.</li>
     * </ol>
     *
     * @return the first build from the imported file
     * @throws IllegalArgumentException on any validation failure
     */
    public synchronized Build importFromFile(File file) {
        String json;
        try {
            json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid build file: cannot read file", e);
        }

        // 1. Parse JSON
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("Invalid build file: missing 'builds' array");
            }
            root = el.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid build file: malformed JSON", e);
        }

        // 2. Must have builds array
        if (!root.has("builds") || !root.get("builds").isJsonArray()) {
            throw new IllegalArgumentException("Invalid build file: missing 'builds' array");
        }
        JsonArray buildsArray = root.getAsJsonArray("builds");

        // 3. Max import count
        if (buildsArray.size() > MAX_IMPORT_BUILDS) {
            throw new IllegalArgumentException("Invalid build file: max " + MAX_IMPORT_BUILDS + " builds per file");
        }

        // 4 + 5. Validate each build
        List<Build> imported = new ArrayList<>();
        for (JsonElement el : buildsArray) {
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("Invalid build file: each entry must be a JSON object");
            }
            JsonObject obj = el.getAsJsonObject();

            // Validate id
            String id = obj.has("id") && !obj.get("id").isJsonNull()
                    ? obj.get("id").getAsString() : null;
            if (id == null || !VALID_ID.matcher(id).matches()) {
                String display = id != null ? id : "(null)";
                throw new IllegalArgumentException(
                        "Invalid build file: id '" + display + "' contains invalid characters");
            }

            // Silently truncate string fields
            truncateField(obj, "name");
            truncateField(obj, "description");
            truncateField(obj, "author");

            Build build = GSON.fromJson(obj, Build.class);
            imported.add(build);
        }

        // Merge into saved map (imported overwrites local)
        for (Build b : imported) {
            savedBuilds.put(b.getId(), b);
        }
        persist(savedBuilds.values());

        return imported.get(0);
    }

    /**
     * Writes a single build to {@code file} using the standard
     * {@code {"builds": [...]}} envelope format.
     *
     * @throws RuntimeException wrapping {@link IOException} if the write fails
     */
    public void exportToFile(Build build, File file) {
        BuildsWrapper wrapper = new BuildsWrapper();
        wrapper.builds = Collections.singletonList(build);
        String json = GSON.toJson(wrapper);
        try {
            Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Failed to export build to file: " + file, e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<Build> loadSeeds() {
        try (InputStream is = BuildStore.class.getResourceAsStream("/builds.json")) {
            if (is == null) {
                log.warn("BuildStore: /builds.json not found on classpath — starting with no seeds");
                return new ArrayList<>();
            }
            BuildsWrapper wrapper = GSON.fromJson(
                    new InputStreamReader(is, StandardCharsets.UTF_8), BuildsWrapper.class);
            if (wrapper == null || wrapper.builds == null) {
                log.warn("BuildStore: /builds.json parsed but contained no builds");
                return new ArrayList<>();
            }
            return new ArrayList<>(wrapper.builds);
        } catch (IOException | JsonSyntaxException e) {
            log.warn("BuildStore: failed to load /builds.json from classpath — {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private LinkedHashMap<String, Build> loadSaved() {
        LinkedHashMap<String, Build> map = new LinkedHashMap<>();
        if (savesFile == null || !savesFile.exists()) {
            return map;
        }
        try {
            String json = new String(Files.readAllBytes(savesFile.toPath()), StandardCharsets.UTF_8);
            if (json.isEmpty()) return map;
            BuildsWrapper wrapper = GSON.fromJson(json, BuildsWrapper.class);
            if (wrapper != null && wrapper.builds != null) {
                for (Build b : wrapper.builds) {
                    if (b != null && b.getId() != null) {
                        map.put(b.getId(), b);
                    }
                }
            }
        } catch (IOException | JsonSyntaxException e) {
            log.warn("BuildStore: failed to load saved builds from {} — {}", savesFile, e.getMessage());
        }
        return map;
    }

    private void persist(Collection<Build> builds) {
        if (savesFile == null) return;
        File parent = savesFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            BuildsWrapper wrapper = new BuildsWrapper();
            wrapper.builds = new ArrayList<>(builds);
            File tmp = new File(savesFile.getParentFile(), savesFile.getName() + ".tmp");
            Files.write(tmp.toPath(), GSON.toJson(wrapper).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), savesFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Swallow — build state is best-effort. Plugin must not crash because
            // the filesystem is full or the directory is not writable.
            log.warn("BuildStore: failed to persist builds — {}", e.getMessage());
        }
    }

    private static void truncateField(JsonObject obj, String field) {
        if (obj.has(field) && obj.get(field).isJsonPrimitive()) {
            String val = obj.get(field).getAsString();
            if (val.length() > MAX_FIELD_LENGTH) {
                obj.addProperty(field, val.substring(0, MAX_FIELD_LENGTH));
            }
        }
    }
}
