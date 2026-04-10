package com.leaguesai.agent;

import com.leaguesai.data.GearRepository;
import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Build;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.GearItem;
import com.leaguesai.data.model.GearSlot;
import com.leaguesai.data.model.Task;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

public class BuildExpanderTest {

    @Mock
    private GearRepository gearRepository;

    @Mock
    private TaskRepository taskRepository;

    private GoalPlanner realPlanner;
    private BuildExpander buildExpander;

    private Task taskTbz100;
    private Task taskTbz123;
    private Task taskXyz;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Real GoalPlanner backed by mock TaskRepository
        realPlanner = new GoalPlanner(taskRepository);
        buildExpander = new BuildExpander(gearRepository, taskRepository, realPlanner);

        // task-tbz-100: no prerequisites (used as a prereq for task-tbz-123)
        taskTbz100 = Task.builder()
                .id("task-tbz-100")
                .name("Prerequisite Task")
                .difficulty(Difficulty.EASY)
                .area("Misthalin")
                .tasksRequired(Collections.emptyList())
                .points(10)
                .build();

        // task-tbz-123: terminal task, requires task-tbz-100
        taskTbz123 = Task.builder()
                .id("task-tbz-123")
                .name("Fire Cape Task")
                .difficulty(Difficulty.HARD)
                .area("Misthalin")
                .tasksRequired(Collections.singletonList("task-tbz-100"))
                .points(50)
                .build();

        // task-xyz: standalone task returned by findByTargetItemId(6570)
        taskXyz = Task.builder()
                .id("task-xyz")
                .name("TzTok-Jad Task")
                .difficulty(Difficulty.ELITE)
                .area("Karamja")
                .tasksRequired(Collections.emptyList())
                .points(100)
                .build();

        // Common stub setup for GoalPlanner's getById + getPrerequisites
        when(taskRepository.getById("task-tbz-100")).thenReturn(taskTbz100);
        when(taskRepository.getById("task-tbz-123")).thenReturn(taskTbz123);
        when(taskRepository.getById("task-xyz")).thenReturn(taskXyz);

        when(taskRepository.getPrerequisites("task-tbz-100")).thenReturn(Collections.emptyList());
        when(taskRepository.getPrerequisites("task-tbz-123"))
                .thenReturn(Collections.singletonList(taskTbz100));
        when(taskRepository.getPrerequisites("task-xyz")).thenReturn(Collections.emptyList());
    }

    /**
     * Test 1: build with task overrides returns full DAG chain including prereqs.
     */
    @Test
    public void expand_with_task_overrides_returns_full_chain() {
        GearItem fireCape = GearItem.builder()
                .id("fire_cape")
                .wikiItemId(6570)
                .name("Fire cape")
                .slot(GearSlot.CAPE)
                .taskOverrides(Collections.singletonList("task-tbz-123"))
                .build();

        when(gearRepository.findById("fire_cape")).thenReturn(fireCape);

        Map<GearSlot, String> gear = new LinkedHashMap<>();
        gear.put(GearSlot.CAPE, "fire_cape");

        Build build = Build.builder()
                .id("melee_build_v1")
                .name("Melee Bosser")
                .gear(gear)
                .build();

        CompositeGoal result = buildExpander.expand(build, null);

        assertNotNull(result);
        assertTrue("Build goal should be reachable", result.isReachable());

        List<Task> batch = result.getTaskBatch();
        assertNotNull(batch);
        assertFalse("taskBatch should not be empty", batch.isEmpty());

        List<String> ids = new ArrayList<>();
        for (Task t : batch) ids.add(t.getId());

        assertTrue("Batch must contain terminal task-tbz-123", ids.contains("task-tbz-123"));
        assertTrue("Batch must contain prereq task-tbz-100", ids.contains("task-tbz-100"));

        // Prereq must come before terminal
        assertTrue("task-tbz-100 must appear before task-tbz-123 in topo order",
                ids.indexOf("task-tbz-100") < ids.indexOf("task-tbz-123"));
    }

    /**
     * Test 2: build with wikiItemId SQL match (no overrides) returns tasks from findByTargetItemId.
     */
    @Test
    public void expand_with_sql_match_returns_tasks() {
        GearItem jadCape = GearItem.builder()
                .id("fire_cape")
                .wikiItemId(6570)
                .name("Fire cape")
                .slot(GearSlot.CAPE)
                .taskOverrides(Collections.emptyList())
                .build();

        when(gearRepository.findById("fire_cape")).thenReturn(jadCape);
        when(taskRepository.findByTargetItemId(6570))
                .thenReturn(Collections.singletonList(taskXyz));

        Map<GearSlot, String> gear = new LinkedHashMap<>();
        gear.put(GearSlot.CAPE, "fire_cape");

        Build build = Build.builder()
                .id("melee_build_v1")
                .name("Jad Build")
                .gear(gear)
                .build();

        CompositeGoal result = buildExpander.expand(build, null);

        assertNotNull(result);
        assertTrue("Build goal should be reachable", result.isReachable());

        List<Task> batch = result.getTaskBatch();
        assertNotNull(batch);
        assertFalse("taskBatch should not be empty", batch.isEmpty());

        List<String> ids = new ArrayList<>();
        for (Task t : batch) ids.add(t.getId());
        assertTrue("Batch should contain task-xyz", ids.contains("task-xyz"));
    }

    /**
     * Test 3: build with null or empty gear returns goals-only result.
     */
    @Test
    public void expand_empty_gear_returns_goals_only() {
        // null gear map
        Build buildNullGear = Build.builder()
                .id("empty_build")
                .name("Empty Build")
                .gear(null)
                .build();

        CompositeGoal resultNull = buildExpander.expand(buildNullGear, null);
        assertNotNull(resultNull);
        assertTrue("Should be reachable in goals-only mode", resultNull.isReachable());
        assertTrue("taskBatch should be empty for null gear",
                resultNull.getTaskBatch().isEmpty());
        assertEquals(0, resultNull.getPointsGap());

        // empty gear map
        Build buildEmptyGear = Build.builder()
                .id("empty_build_2")
                .name("Empty Build 2")
                .gear(new LinkedHashMap<>())
                .build();

        CompositeGoal resultEmpty = buildExpander.expand(buildEmptyGear, null);
        assertNotNull(resultEmpty);
        assertTrue("Should be reachable in goals-only mode", resultEmpty.isReachable());
        assertTrue("taskBatch should be empty for empty gear",
                resultEmpty.getTaskBatch().isEmpty());
        assertEquals(0, resultEmpty.getPointsGap());
    }

    /**
     * Test 4: gear map references an ID not in GearRepository — skipped gracefully, goals-only.
     */
    @Test
    public void expand_unknown_gear_id_is_skipped_gracefully() {
        when(gearRepository.findById("nonexistent_item")).thenReturn(null);

        Map<GearSlot, String> gear = new LinkedHashMap<>();
        gear.put(GearSlot.BODY, "nonexistent_item");

        Build build = Build.builder()
                .id("ghost_build")
                .name("Ghost Build")
                .gear(gear)
                .build();

        // Must not throw
        CompositeGoal result = buildExpander.expand(build, null);

        assertNotNull("Should return a result even with unknown item", result);
        assertTrue("Should be reachable in goals-only mode", result.isReachable());
        assertTrue("taskBatch should be empty — unknown item skipped",
                result.getTaskBatch().isEmpty());
    }

    /**
     * Test 5: item with wikiItemId=0 must never trigger findByTargetItemId(0).
     */
    @Test
    public void expand_wikiItemId_zero_skips_sql_lookup() {
        GearItem zeroIdItem = GearItem.builder()
                .id("unknown_gear")
                .wikiItemId(0)
                .name("Unknown gear")
                .slot(GearSlot.HEAD)
                .taskOverrides(Collections.emptyList())
                .build();

        when(gearRepository.findById("unknown_gear")).thenReturn(zeroIdItem);

        Map<GearSlot, String> gear = new LinkedHashMap<>();
        gear.put(GearSlot.HEAD, "unknown_gear");

        Build build = Build.builder()
                .id("zero_id_build")
                .name("Zero ID Build")
                .gear(gear)
                .build();

        buildExpander.expand(build, null);

        // findByTargetItemId must never be called with 0
        verify(taskRepository, never()).findByTargetItemId(0);
    }

    /**
     * Test 6: expand(null, ctx) returns goals-only result without throwing.
     */
    @Test
    public void expand_null_build_returns_goals_only() {
        PlayerContext ctx = PlayerContext.builder()
                .leaguePoints(0)
                .completedTasks(Collections.emptySet())
                .build();

        CompositeGoal result = buildExpander.expand(null, ctx);

        assertNotNull("Should return a result for null build", result);
        assertTrue("Should be reachable", result.isReachable());
        assertTrue("taskBatch should be empty", result.getTaskBatch().isEmpty());
        assertEquals(0, result.getPointsGap());
    }
}
