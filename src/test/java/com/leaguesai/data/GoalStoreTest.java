package com.leaguesai.data;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Round-trip + corruption-safety coverage for {@link GoalStore}.
 */
public class GoalStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void addAndReadBackRelicGoal() throws Exception {
        File f = new File(tmp.getRoot(), "goals.json");
        GoalStore store = new GoalStore(f);
        assertFalse(store.isRelicGoal("grimoire"));

        store.addRelicGoal("grimoire");
        assertTrue(store.isRelicGoal("grimoire"));

        // New instance must see the persisted write.
        GoalStore reloaded = new GoalStore(f);
        assertTrue("Relic goal persisted to disk", reloaded.isRelicGoal("grimoire"));
    }

    @Test
    public void removeRelicGoal() throws Exception {
        File f = new File(tmp.getRoot(), "goals.json");
        GoalStore store = new GoalStore(f);
        store.addRelicGoal("grimoire");
        store.removeRelicGoal("grimoire");
        assertFalse(store.isRelicGoal("grimoire"));

        GoalStore reloaded = new GoalStore(f);
        assertFalse(reloaded.isRelicGoal("grimoire"));
    }

    @Test
    public void areaAndPactGoalsIndependent() {
        GoalStore store = new GoalStore(new File(tmp.getRoot(), "goals.json"));
        store.addAreaGoal("Wilderness");
        store.addPactGoal("pact-aa");

        assertTrue(store.isAreaGoal("Wilderness"));
        assertTrue(store.isPactGoal("pact-aa"));
        assertFalse("Area goal does not leak into relic set", store.isRelicGoal("Wilderness"));
        assertFalse("Pact goal does not leak into area set", store.isAreaGoal("pact-aa"));
    }

    @Test
    public void isUnlockedStartsFalseAndTogglesOnMark() {
        GoalStore store = new GoalStore(new File(tmp.getRoot(), "goals.json"));
        assertFalse(store.isUnlocked("grimoire"));
        store.markUnlocked("grimoire");
        assertTrue(store.isUnlocked("grimoire"));
    }

    @Test
    public void corruptFileStartsFresh() throws Exception {
        File f = new File(tmp.getRoot(), "goals.json");
        Files.write(f.toPath(), "{{{ not json".getBytes(StandardCharsets.UTF_8));

        // Must not throw.
        GoalStore store = new GoalStore(f);
        assertFalse(store.isRelicGoal("anything"));
        // Writing a new goal repairs the file.
        store.addRelicGoal("grimoire");
        GoalStore reloaded = new GoalStore(f);
        assertTrue(reloaded.isRelicGoal("grimoire"));
    }

    @Test
    public void nullAndEmptyIdsAreIgnored() {
        GoalStore store = new GoalStore(new File(tmp.getRoot(), "goals.json"));
        store.addRelicGoal(null);
        store.addRelicGoal("");
        store.addAreaGoal(null);
        store.addPactGoal(null);
        store.markUnlocked(null);
        assertTrue(store.getRelicGoals().isEmpty());
        assertTrue(store.getAreaGoals().isEmpty());
        assertTrue(store.getPactGoals().isEmpty());
    }
}
