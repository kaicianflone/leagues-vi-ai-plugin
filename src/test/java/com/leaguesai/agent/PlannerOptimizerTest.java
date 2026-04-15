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
    public void testSameLocationTasksAllReturned() {
        // All tasks at the same tile — proximity order is insertion order.
        // The optimizer must not drop any tasks regardless of difficulty tie.
        Task hard = Task.builder()
                .id("HARD").name("Hard Task").difficulty(Difficulty.HARD)
                .area("Misthalin").location(new WorldPoint(3200, 3200, 0)).build();
        Task easy = Task.builder()
                .id("EASY").name("Easy Task").difficulty(Difficulty.EASY)
                .area("Misthalin").location(new WorldPoint(3200, 3200, 0)).build();
        Task medium = Task.builder()
                .id("MEDIUM").name("Medium Task").difficulty(Difficulty.MEDIUM)
                .area("Misthalin").location(new WorldPoint(3200, 3200, 0)).build();

        List<Task> optimized = PlannerOptimizer.optimizeOrder(
                Arrays.asList(hard, easy, medium), new WorldPoint(3200, 3200, 0));

        assertEquals("All 3 tasks must be returned", 3, optimized.size());
        assertTrue("EASY must be present", optimized.stream().anyMatch(t -> "EASY".equals(t.getId())));
        assertTrue("MEDIUM must be present", optimized.stream().anyMatch(t -> "MEDIUM".equals(t.getId())));
        assertTrue("HARD must be present", optimized.stream().anyMatch(t -> "HARD".equals(t.getId())));
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
                .id("T1").name("Task 1").difficulty(Difficulty.MEDIUM)
                .area("Varrock").location(new WorldPoint(3210, 3424, 0)).build();
        Task t2 = Task.builder()
                .id("T2").name("Task 2").difficulty(Difficulty.EASY)
                .area("Varrock").location(new WorldPoint(3210, 3424, 0)).build();

        // Null player location: walk starts from first task. Should not throw,
        // all tasks returned.
        List<Task> result = PlannerOptimizer.optimizeOrder(Arrays.asList(t1, t2), null);
        assertEquals("Should return all 2 tasks", 2, result.size());
        assertTrue("T1 present", result.stream().anyMatch(t -> "T1".equals(t.getId())));
        assertTrue("T2 present", result.stream().anyMatch(t -> "T2".equals(t.getId())));
    }

    @Test
    public void testProximityInterleavesEasyAndMedium() {
        // Two easy tasks and one medium at different locations.
        // Player starts near E1 and M1 (both at x=100). E2 is far away (x=1000).
        // Expected walk: E1 → M1 (both near player) → E2 (far)
        Task e1 = Task.builder().id("E1").name("Easy 1").difficulty(Difficulty.EASY)
                .area("test").location(new WorldPoint(100, 100, 0)).build();
        Task m1 = Task.builder().id("M1").name("Medium 1").difficulty(Difficulty.MEDIUM)
                .area("test").location(new WorldPoint(102, 100, 0)).build(); // 2 tiles from E1
        Task e2 = Task.builder().id("E2").name("Easy 2").difficulty(Difficulty.EASY)
                .area("test").location(new WorldPoint(1000, 100, 0)).build(); // far away

        List<Task> result = PlannerOptimizer.optimizeOrder(
                Arrays.asList(e2, m1, e1), new WorldPoint(100, 100, 0));

        assertEquals(3, result.size());
        int idxE1 = indexOf(result, "E1");
        int idxM1 = indexOf(result, "M1");
        int idxE2 = indexOf(result, "E2");

        // E1 and M1 should both precede E2 (they're near the player, E2 is far)
        assertTrue("E1 before E2 (proximity)", idxE1 < idxE2);
        assertTrue("M1 before E2 (proximity)", idxM1 < idxE2);
    }

    @Test
    public void testAreaHubFallbackUsedForOrdering() {
        // Tasks with no precise location but known Leagues VI area — hub fallback used.
        // varlamore hub (1842,3043) is far from karamja hub (2798,3212).
        // Player at varlamore hub — varlamore task should come first.
        Task varTask = Task.builder().id("VAR").name("Varlamore Task").difficulty(Difficulty.EASY)
                .area("varlamore").location(null).build();
        Task karTask = Task.builder().id("KAR").name("Karamja Task").difficulty(Difficulty.EASY)
                .area("karamja").location(null).build();

        WorldPoint varlamore = new WorldPoint(1842, 3043, 0);
        List<Task> result = PlannerOptimizer.optimizeOrder(Arrays.asList(karTask, varTask), varlamore);

        assertEquals(2, result.size());
        assertEquals("Varlamore task should be first (player is there)", "VAR", result.get(0).getId());
        assertEquals("Karamja task second (far away)", "KAR", result.get(1).getId());
    }

    private int indexOf(List<Task> tasks, String id) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getId().equals(id)) return i;
        }
        return -1;
    }
}
