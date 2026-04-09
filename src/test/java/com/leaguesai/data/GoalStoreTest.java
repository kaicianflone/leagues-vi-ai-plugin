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

    // ----- Phase 2 PR 3: pact selection + respec mechanics ----------------

    @Test
    public void pactSelectionRoundTrip() {
        File f = new File(tmp.getRoot(), "goals.json");
        GoalStore store = new GoalStore(f);

        assertEquals(0, store.getSelectedPactCount());
        assertFalse(store.isPactSelected("pact-aa"));
        assertTrue(store.canSelectAnotherPact());

        assertTrue(store.selectPact("pact-aa"));
        assertTrue(store.isPactSelected("pact-aa"));
        assertEquals(1, store.getSelectedPactCount());

        // Second identical select is idempotent (returns false, no change).
        assertFalse(store.selectPact("pact-aa"));
        assertEquals(1, store.getSelectedPactCount());

        assertTrue(store.deselectPact("pact-aa"));
        assertFalse(store.isPactSelected("pact-aa"));
        assertEquals(0, store.getSelectedPactCount());

        // Persists across reloads.
        store.selectPact("pact-aa");
        store.selectPact("pact-b1");
        GoalStore reloaded = new GoalStore(f);
        assertEquals(2, reloaded.getSelectedPactCount());
        assertTrue(reloaded.isPactSelected("pact-aa"));
        assertTrue(reloaded.isPactSelected("pact-b1"));
    }

    @Test
    public void pactBudgetCapEnforced() {
        GoalStore store = new GoalStore(new File(tmp.getRoot(), "goals.json"));
        for (int i = 0; i < GoalStore.MAX_PACT_SLOTS; i++) {
            assertTrue("should fit pact " + i, store.selectPact("pact-" + i));
        }
        assertEquals(GoalStore.MAX_PACT_SLOTS, store.getSelectedPactCount());
        assertFalse("budget full, canSelectAnotherPact must be false",
                store.canSelectAnotherPact());
        assertFalse("41st pact selection must be rejected",
                store.selectPact("pact-overflow"));
        assertEquals(GoalStore.MAX_PACT_SLOTS, store.getSelectedPactCount());
    }

    @Test
    public void respecCounterAndExhaustion() {
        GoalStore store = new GoalStore(new File(tmp.getRoot(), "goals.json"));
        store.selectPact("pact-aa");
        store.selectPact("pact-b1");
        assertEquals(0, store.getRespecsUsed());
        assertEquals(GoalStore.MAX_RESPECS, store.getRespecsRemaining());
        assertTrue(store.canRespec());

        // Respec 1: wipes selection, counter ticks.
        assertTrue(store.resetPacts());
        assertEquals(0, store.getSelectedPactCount());
        assertEquals(1, store.getRespecsUsed());
        assertEquals(GoalStore.MAX_RESPECS - 1, store.getRespecsRemaining());

        // Burn through the remaining 2 respecs.
        store.selectPact("pact-c1");
        assertTrue(store.resetPacts());
        store.selectPact("pact-d1");
        assertTrue(store.resetPacts());
        assertEquals(GoalStore.MAX_RESPECS, store.getRespecsUsed());
        assertEquals(0, store.getRespecsRemaining());
        assertFalse("all respecs burned, canRespec must be false", store.canRespec());

        // 4th respec attempt must be rejected and leave state alone.
        store.selectPact("pact-e1");
        assertFalse(store.resetPacts());
        assertEquals(1, store.getSelectedPactCount());
        assertEquals(GoalStore.MAX_RESPECS, store.getRespecsUsed());
    }

    @Test
    public void deselectOfUnknownIdIsNoOp() {
        GoalStore store = new GoalStore(new File(tmp.getRoot(), "goals.json"));
        assertFalse(store.deselectPact("never-selected"));
        assertFalse(store.deselectPact(null));
        assertFalse(store.deselectPact(""));
    }

    @Test
    public void nullOrEmptyPactIdIsRejected() {
        GoalStore store = new GoalStore(new File(tmp.getRoot(), "goals.json"));
        assertFalse(store.selectPact(null));
        assertFalse(store.selectPact(""));
        assertEquals(0, store.getSelectedPactCount());
    }

    @Test
    public void migrationFromPhase1JsonWithoutNewFields() throws Exception {
        // Simulate a goals.json written by the Phase 1 GoalStore — no
        // selectedPactIds, no respecsUsed. The Phase 2 loader must default
        // them instead of throwing or crashing.
        File f = new File(tmp.getRoot(), "goals.json");
        String phase1Json = "{"
                + "\"relicGoals\":[\"grimoire\"],"
                + "\"areaGoals\":[],"
                + "\"pactGoals\":[],"
                + "\"unlocked\":[]"
                + "}";
        Files.write(f.toPath(), phase1Json.getBytes(StandardCharsets.UTF_8));

        GoalStore store = new GoalStore(f);
        // Existing fields preserved.
        assertTrue(store.isRelicGoal("grimoire"));
        // New fields defaulted.
        assertEquals(0, store.getSelectedPactCount());
        assertEquals(0, store.getRespecsUsed());
        assertTrue(store.canSelectAnotherPact());
        assertTrue(store.canRespec());

        // Writing a Phase 2 field then reloading produces the full shape.
        store.selectPact("pact-aa");
        GoalStore reloaded = new GoalStore(f);
        assertTrue(reloaded.isPactSelected("pact-aa"));
        assertTrue(reloaded.isRelicGoal("grimoire"));
    }
}
