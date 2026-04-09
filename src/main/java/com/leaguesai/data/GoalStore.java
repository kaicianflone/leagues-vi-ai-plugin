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
 * Persists which relics / areas / pacts the user has pinned as goals, which
 * are unlocked, and (Phase 2) which pacts the user has actively selected for
 * their 40-slot Leagues VI pact allocation. Separate from the SQLite read
 * model (which holds wiki data, not user state).
 *
 * <p>Backed by a small JSON file at
 * {@code ~/.runelite/leagues-ai/data/goals.json}. Writes go through a
 * temp-file-and-rename dance so a crash mid-write can't leave a truncated
 * file on disk.
 *
 * <p>Phase 2 pact allocation model: Leagues VI lets the player pick up to
 * {@link #MAX_PACT_SLOTS} pacts during the league, with {@link #MAX_RESPECS}
 * full resets available if they want to reshuffle. This class tracks the
 * current selection set + the respec counter and exposes idempotent methods
 * for the UI to drive. See {@code UnlockablesPanel.buildPactsSection} for
 * the UI side.
 *
 * <p>{@link #isUnlocked(String)} returns {@code false} for ids that haven't
 * been explicitly marked. Auto-detection from in-game events is a
 * post-launch task tracked in {@code CLAUDE.md}.
 */
public class GoalStore {

    /** Hard cap on simultaneous pact selections per the Demonic Pacts League rules. */
    public static final int MAX_PACT_SLOTS = 40;
    /** Number of full resets the player gets during the league. */
    public static final int MAX_RESPECS = 3;

    private static final Gson GSON = new Gson();

    private final File file;
    private State state;

    public GoalStore(File file) {
        this.file = file;
        this.state = load();
    }

    // -------------------------------------------------------------------------
    // Pin-a-goal API (relic / area / pact targets the planner should care about)
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
    // Pact allocation API (Phase 2 — 40 slots + 3 respecs)
    // -------------------------------------------------------------------------

    /** Pacts the player has actively selected for their current allocation. */
    public synchronized Set<String> getSelectedPacts() {
        return Collections.unmodifiableSet(state.selectedPactIds);
    }

    /** Number of pacts currently selected. Always {@code <= MAX_PACT_SLOTS}. */
    public synchronized int getSelectedPactCount() {
        return state.selectedPactIds.size();
    }

    public synchronized boolean isPactSelected(String id) {
        return id != null && state.selectedPactIds.contains(id);
    }

    /**
     * {@code true} when the player has room in their {@link #MAX_PACT_SLOTS}
     * budget to pick another pact. A pact that is already selected does NOT
     * count against this — the caller should allow deselection even at the cap.
     */
    public synchronized boolean canSelectAnotherPact() {
        return state.selectedPactIds.size() < MAX_PACT_SLOTS;
    }

    /**
     * Add a pact to the active selection. No-op if the id is already selected
     * or the budget is full. Returns {@code true} if the selection changed.
     */
    public synchronized boolean selectPact(String id) {
        if (id == null || id.isEmpty()) return false;
        if (state.selectedPactIds.contains(id)) return false;
        if (!canSelectAnotherPact()) return false;
        state.selectedPactIds.add(id);
        save();
        return true;
    }

    /** Remove a pact from the active selection. Returns {@code true} if changed. */
    public synchronized boolean deselectPact(String id) {
        if (id == null || id.isEmpty()) return false;
        if (!state.selectedPactIds.remove(id)) return false;
        save();
        return true;
    }

    public synchronized int getRespecsUsed() {
        return state.respecsUsed;
    }

    public synchronized int getRespecsRemaining() {
        return Math.max(0, MAX_RESPECS - state.respecsUsed);
    }

    public synchronized boolean canRespec() {
        return state.respecsUsed < MAX_RESPECS;
    }

    /**
     * Clear the entire pact selection and burn one respec. Returns {@code true}
     * if the respec was applied; {@code false} if the player has already used
     * all {@link #MAX_RESPECS} resets. The UI should show a confirmation
     * dialog BEFORE calling this.
     */
    public synchronized boolean resetPacts() {
        if (!canRespec()) return false;
        state.selectedPactIds.clear();
        state.respecsUsed++;
        save();
        return true;
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

        // Phase 2 additions. Old goals.json files written before these fields
        // existed will deserialize with null here and get defaulted by
        // ensureInitialized() — no explicit migration step required.
        Set<String> selectedPactIds = new LinkedHashSet<>();
        int respecsUsed = 0;

        State ensureInitialized() {
            if (relicGoals == null) relicGoals = new LinkedHashSet<>();
            if (areaGoals == null) areaGoals = new LinkedHashSet<>();
            if (pactGoals == null) pactGoals = new LinkedHashSet<>();
            if (unlocked == null) unlocked = new LinkedHashSet<>();
            if (selectedPactIds == null) selectedPactIds = new LinkedHashSet<>();
            // respecsUsed is a primitive int — Gson defaults it to 0 for missing fields.
            return this;
        }
    }
}
