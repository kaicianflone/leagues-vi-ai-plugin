package com.leaguesai.agent;

import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class PlannerOptimizerTest {

    @Test
    public void testGroupsByArea() {
        Task t1 = Task.builder()
                .id("1")
                .name("Varrock Task 1")
                .difficulty(Difficulty.EASY)
                .area("Varrock")
                .location(new WorldPoint(3210, 3424, 0))
                .build();

        Task t2 = Task.builder()
                .id("2")
                .name("Lumbridge Task 1")
                .difficulty(Difficulty.EASY)
                .area("Lumbridge")
                .location(new WorldPoint(3222, 3218, 0))
                .build();

        Task t3 = Task.builder()
                .id("3")
                .name("Varrock Task 2")
                .difficulty(Difficulty.MEDIUM)
                .area("Varrock")
                .location(new WorldPoint(3210, 3424, 0))
                .build();

        // Player is at Varrock — Varrock tasks should come first
        WorldPoint playerLoc = new WorldPoint(3210, 3424, 0);

        List<Task> optimized = PlannerOptimizer.optimizeOrder(Arrays.asList(t1, t2, t3), playerLoc);

        assertEquals("Should return all 3 tasks", 3, optimized.size());

        // Verify Varrock tasks (t1 and t3) appear before Lumbridge (t2) since player is at Varrock
        int idx1 = indexOf(optimized, "1");
        int idx2 = indexOf(optimized, "2");
        int idx3 = indexOf(optimized, "3");

        assertTrue("Varrock tasks should come before Lumbridge when player is at Varrock",
                idx1 < idx2 && idx3 < idx2);
    }

    @Test
    public void testSortsByDifficultyWithinArea() {
        Task hard = Task.builder()
                .id("HARD")
                .name("Hard Task")
                .difficulty(Difficulty.HARD)
                .area("Misthalin")
                .location(new WorldPoint(3200, 3200, 0))
                .build();

        Task easy = Task.builder()
                .id("EASY")
                .name("Easy Task")
                .difficulty(Difficulty.EASY)
                .area("Misthalin")
                .location(new WorldPoint(3200, 3200, 0))
                .build();

        Task medium = Task.builder()
                .id("MEDIUM")
                .name("Medium Task")
                .difficulty(Difficulty.MEDIUM)
                .area("Misthalin")
                .location(new WorldPoint(3200, 3200, 0))
                .build();

        List<Task> optimized = PlannerOptimizer.optimizeOrder(
                Arrays.asList(hard, easy, medium), new WorldPoint(3200, 3200, 0));

        assertEquals("Should return all 3 tasks", 3, optimized.size());

        int easyIdx = indexOf(optimized, "EASY");
        int medIdx = indexOf(optimized, "MEDIUM");
        int hardIdx = indexOf(optimized, "HARD");

        assertTrue("EASY should come before MEDIUM", easyIdx < medIdx);
        assertTrue("MEDIUM should come before HARD", medIdx < hardIdx);
    }

    @Test
    public void testNullLocationSafe() {
        Task withLoc = Task.builder()
                .id("LOC")
                .name("Task With Location")
                .difficulty(Difficulty.EASY)
                .area("Varrock")
                .location(new WorldPoint(3210, 3424, 0))
                .build();

        Task noLoc = Task.builder()
                .id("NOLOC")
                .name("Task Without Location")
                .difficulty(Difficulty.EASY)
                .area("Varrock")
                .location(null)
                .build();

        // Should not throw when a task has null location
        List<Task> result = PlannerOptimizer.optimizeOrder(
                Arrays.asList(withLoc, noLoc), new WorldPoint(3210, 3424, 0));

        assertEquals("Should return all 2 tasks", 2, result.size());
    }

    @Test
    public void testNullPlayerLocationSafe() {
        Task t1 = Task.builder()
                .id("T1")
                .name("Task 1")
                .difficulty(Difficulty.MEDIUM)
                .area("Varrock")
                .location(new WorldPoint(3210, 3424, 0))
                .build();

        Task t2 = Task.builder()
                .id("T2")
                .name("Task 2")
                .difficulty(Difficulty.EASY)
                .area("Varrock")
                .location(new WorldPoint(3210, 3424, 0))
                .build();

        // Should not throw when playerLocation is null — just skip distance sort
        List<Task> result = PlannerOptimizer.optimizeOrder(Arrays.asList(t1, t2), null);

        assertEquals("Should return all 2 tasks", 2, result.size());
        // Within-area difficulty sort still applies
        assertEquals("EASY (T2) should come first within area", "T2", result.get(0).getId());
    }

    private int indexOf(List<Task> tasks, String id) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getId().equals(id)) return i;
        }
        return -1;
    }
}
