package com.leaguesai.data;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persists which relics / areas / pacts the user has pinned as goals, plus
 * which are unlocked. Separate from the SQLite read model (which holds wiki
 * data, not user state).
 *
 * <p>Backed by a small JSON file at
 * {@code ~/.runelite/leagues-ai/data/goals.json}. Writes go through a
 * temp-file-and-rename dance so a crash mid-write can't leave a truncated
 * file on disk.
 *
 * <p>Phase 1: {@link #isUnlocked(String)} always returns {@code false} for
 * ids that haven't been explicitly marked. Phase 2 will hook this into
 * in-game events.
 */
public class GoalStore {

    private static final Gson GSON = new Gson();

    private final File file;
    private State state;

    public GoalStore(File file) {
        this.file = file;
        this.state = load();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public synchronized Set<String> getRelicGoals() {
        return Collections.unmodifiableSet(state.relicGoals);
    }

    public synchronized Set<String> getAreaGoals() {
        return Collections.unmodifiableSet(state.areaGoals);
    }

    public synchronized Set<String> getPactGoals() {
        return Collections.unmodifiableSet(state.pactGoals);
    }

    public synchronized boolean isRelicGoal(String id) {
        return id != null && state.relicGoals.contains(id);
    }

    public synchronized boolean isAreaGoal(String id) {
        return id != null && state.areaGoals.contains(id);
    }

    public synchronized boolean isPactGoal(String id) {
        return id != null && state.pactGoals.contains(id);
    }

    public synchronized boolean isUnlocked(String id) {
        return id != null && state.unlocked.contains(id);
    }

    public synchronized void addRelicGoal(String id) {
        if (id == null || id.isEmpty()) return;
        state.relicGoals.add(id);
        save();
    }

    public synchronized void addAreaGoal(String id) {
        if (id == null || id.isEmpty()) return;
        state.areaGoals.add(id);
        save();
    }

    public synchronized void addPactGoal(String id) {
        if (id == null || id.isEmpty()) return;
        state.pactGoals.add(id);
        save();
    }

    public synchronized void removeRelicGoal(String id) {
        if (state.relicGoals.remove(id)) save();
    }

    public synchronized void removeAreaGoal(String id) {
        if (state.areaGoals.remove(id)) save();
    }

    public synchronized void removePactGoal(String id) {
        if (state.pactGoals.remove(id)) save();
    }

    public synchronized void markUnlocked(String id) {
        if (id == null || id.isEmpty()) return;
        if (state.unlocked.add(id)) save();
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private State load() {
        if (file == null || !file.exists()) {
            return new State();
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (json.isEmpty()) return new State();
            Type type = new TypeToken<State>() {}.getType();
            State loaded = GSON.fromJson(json, type);
            return loaded != null ? loaded.ensureInitialized() : new State();
        } catch (IOException | JsonSyntaxException e) {
            // Corrupted file — start fresh rather than crashing the plugin.
            return new State();
        }
    }

    private void save() {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.write(tmp.toPath(), GSON.toJson(state).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Swallow — goal state is best-effort. Plugin must not crash because
            // the user's filesystem is full.
        }
    }

    // -------------------------------------------------------------------------
    // Internal state shape (serialized to JSON)
    // -------------------------------------------------------------------------

    private static class State {
        Set<String> relicGoals = new LinkedHashSet<>();
        Set<String> areaGoals = new LinkedHashSet<>();
        Set<String> pactGoals = new LinkedHashSet<>();
        Set<String> unlocked = new LinkedHashSet<>();

        State ensureInitialized() {
            if (relicGoals == null) relicGoals = new LinkedHashSet<>();
            if (areaGoals == null) areaGoals = new LinkedHashSet<>();
            if (pactGoals == null) pactGoals = new LinkedHashSet<>();
            if (unlocked == null) unlocked = new LinkedHashSet<>();
            return this;
        }
    }
}
