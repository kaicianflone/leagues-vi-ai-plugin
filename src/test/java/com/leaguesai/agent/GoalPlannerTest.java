package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GoalPlannerTest {

    @Mock
    private TaskRepository taskRepo;

    private GoalPlanner planner;

    private Task taskA;
    private Task taskB;
    private Task taskC;
    private Task taskD;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        planner = new GoalPlanner(taskRepo);

        // taskA: no prerequisites
        taskA = Task.builder()
                .id("A")
                .name("Task A")
                .difficulty(Difficulty.EASY)
                .area("Misthalin")
                .tasksRequired(Collections.emptyList())
                .build();

        // taskB: requires A
        taskB = Task.builder()
                .id("B")
                .name("Task B")
                .difficulty(Difficulty.MEDIUM)
                .area("Misthalin")
                .tasksRequired(Collections.singletonList("A"))
                .build();

        // taskC: requires A
        taskC = Task.builder()
                .id("C")
                .name("Task C")
                .difficulty(Difficulty.HARD)
                .area("Misthalin")
                .tasksRequired(Collections.singletonList("A"))
                .build();

        // taskD: standalone, no prerequisites
        taskD = Task.builder()
                .id("D")
                .name("Task D")
                .difficulty(Difficulty.EASY)
                .area("Karamja")
                .tasksRequired(Collections.emptyList())
                .build();

        when(taskRepo.getById("A")).thenReturn(taskA);
        when(taskRepo.getById("B")).thenReturn(taskB);
        when(taskRepo.getById("C")).thenReturn(taskC);
        when(taskRepo.getById("D")).thenReturn(taskD);

        // getPrerequisites returns the Task objects for each prereq ID
        when(taskRepo.getPrerequisites("A")).thenReturn(Collections.emptyList());
        when(taskRepo.getPrerequisites("B")).thenReturn(Collections.singletonList(taskA));
        when(taskRepo.getPrerequisites("C")).thenReturn(Collections.singletonList(taskA));
        when(taskRepo.getPrerequisites("D")).thenReturn(Collections.emptyList());
    }

    @Test
    public void testBuildDagResolvesPrereqs() {
        List<Task> targets = Arrays.asList(taskB, taskC);
        Set<String> completed = Collections.emptySet();

        List<Task> dag = planner.buildDag(targets, completed);

        List<String> ids = new ArrayList<>();
        for (Task t : dag) ids.add(t.getId());

        assertTrue("DAG should contain A", ids.contains("A"));
        assertTrue("DAG should contain B", ids.contains("B"));
        assertTrue("DAG should contain C", ids.contains("C"));
        assertFalse("DAG should not contain D", ids.contains("D"));
        assertEquals("DAG should have exactly 3 tasks", 3, dag.size());
    }

    @Test
    public void testBuildDagPrunesCompleted() {
        List<Task> targets = Collections.singletonList(taskB);
        Set<String> completed = Collections.singleton("A");

        List<Task> dag = planner.buildDag(targets, completed);

        List<String> ids = new ArrayList<>();
        for (Task t : dag) ids.add(t.getId());

        assertFalse("Completed task A should be pruned", ids.contains("A"));
        assertTrue("DAG should still contain B", ids.contains("B"));
        assertEquals("DAG should have exactly 1 task", 1, dag.size());
    }

    @Test
    public void testTopologicalSort() {
        List<Task> tasks = Arrays.asList(taskB, taskA, taskC);
        List<Task> sorted = planner.topologicalSort(tasks);

        List<String> ids = new ArrayList<>();
        for (Task t : sorted) ids.add(t.getId());

        int indexA = ids.indexOf("A");
        int indexB = ids.indexOf("B");
        int indexC = ids.indexOf("C");

        assertTrue("A must come before B", indexA < indexB);
        assertTrue("A must come before C", indexA < indexC);
    }

    @Test
    public void testTopologicalSortDetectsCycle() {
        // A requires B, B requires A — mutual cycle
        Task cycleA = Task.builder()
                .id("CA")
                .name("Cycle A")
                .difficulty(Difficulty.EASY)
                .area("Test")
                .tasksRequired(Collections.singletonList("CB"))
                .build();

        Task cycleB = Task.builder()
                .id("CB")
                .name("Cycle B")
                .difficulty(Difficulty.EASY)
                .area("Test")
                .tasksRequired(Collections.singletonList("CA"))
                .build();

        List<Task> cyclicTasks = Arrays.asList(cycleA, cycleB);

        try {
            planner.topologicalSort(cyclicTasks);
            fail("Expected IllegalStateException for cycle");
        } catch (IllegalStateException e) {
            assertTrue("Exception message should mention 'Cycle detected'",
                    e.getMessage().contains("Cycle detected"));
        }
    }

    @Test
    public void testTopologicalSortIgnoresSelfDependency() {
        // A task that requires itself — should not cause infinite loop or false cycle error
        Task selfRef = Task.builder()
                .id("SELF")
                .name("Self Reference")
                .difficulty(Difficulty.EASY)
                .area("Test")
                .tasksRequired(Collections.singletonList("SELF"))
                .build();

        List<Task> tasks = Collections.singletonList(selfRef);

        // Should not throw — self-dependency is silently ignored
        List<Task> sorted = planner.topologicalSort(tasks);
        assertEquals("Should return 1 task", 1, sorted.size());
        assertEquals("Should return the self-referencing task", "SELF", sorted.get(0).getId());
    }

    @Test
    public void testEmptyTasks() {
        List<Task> result = planner.topologicalSort(Collections.emptyList());
        assertNotNull("Result should not be null", result);
        assertTrue("Result should be empty", result.isEmpty());

        List<Task> dagResult = planner.buildDag(Collections.emptyList(), Collections.emptySet());
        assertNotNull("DAG result should not be null", dagResult);
        assertTrue("DAG result should be empty", dagResult.isEmpty());
    }

    @Test
    public void testBuildDagNullInputs() {
        // Both null
        List<Task> r1 = planner.buildDag(null, null);
        assertNotNull(r1);
        assertTrue(r1.isEmpty());

        // Null targets, real completed set
        List<Task> r2 = planner.buildDag(null, Collections.singleton("A"));
        assertNotNull(r2);
        assertTrue(r2.isEmpty());

        // Real targets, null completed set
        List<Task> r3 = planner.buildDag(Collections.singletonList(taskA), null);
        assertNotNull(r3);
        assertEquals(1, r3.size());
        assertEquals("A", r3.get(0).getId());
    }

    // ---------------------------------------------------------------
    // BUILD goal branch tests
    // ---------------------------------------------------------------

    @Test
    public void build_goal_with_terminals_returns_sorted_dag() {
        // Terminal is B (which requires A as a prereq)
        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.BUILD)
                .rawPhrase("build my setup")
                .terminalTaskIds(Collections.singleton("B"))
                .build();

        CompositeGoal result = planner.resolveCompositeGoal(spec, null);

        assertNotNull(result);
        assertTrue("BUILD goal should be reachable", result.isReachable());
        assertEquals("pointsGap should be 0", 0, result.getPointsGap());

        List<Task> batch = result.getTaskBatch();
        assertNotNull(batch);
        assertEquals("Batch should contain terminal + prereq", 2, batch.size());

        List<String> ids = new ArrayList<>();
        for (Task t : batch) ids.add(t.getId());
        assertTrue("Batch must contain A", ids.contains("A"));
        assertTrue("Batch must contain B", ids.contains("B"));

        // A must appear before B (prereq before dependent)
        assertTrue("A must come before B in topo order", ids.indexOf("A") < ids.indexOf("B"));
    }

    @Test
    public void build_goal_empty_terminals_returns_goals_only() {
        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.BUILD)
                .rawPhrase("build with no gear")
                .terminalTaskIds(Collections.emptySet())
                .build();

        CompositeGoal result = planner.resolveCompositeGoal(spec, null);

        assertNotNull(result);
        assertTrue("Should be reachable (goals-only mode)", result.isReachable());
        assertTrue("taskBatch should be empty for goals-only build",
                result.getTaskBatch().isEmpty());
        assertEquals(0, result.getPointsGap());
    }

    @Test
    public void build_goal_null_terminals_returns_goals_only() {
        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.BUILD)
                .rawPhrase("build null terminals")
                .terminalTaskIds(null)
                .build();

        CompositeGoal result = planner.resolveCompositeGoal(spec, null);

        assertNotNull(result);
        assertTrue("Should be reachable (goals-only mode)", result.isReachable());
        assertTrue("taskBatch should be empty for null-terminal build",
                result.getTaskBatch().isEmpty());
        assertEquals(0, result.getPointsGap());
    }

    @Test
    public void build_goal_does_not_run_gap_closing() {
        // Configure repo to throw if getAllTasks() is called — proving the gap-closing
        // path (filterAchievableTasks) never runs for BUILD goals.
        when(taskRepo.getAllTasks()).thenThrow(new RuntimeException("getAllTasks must not be called for BUILD goals"));

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.BUILD)
                .rawPhrase("build test no gap closing")
                .terminalTaskIds(Collections.singleton("D"))
                .build();

        // Should NOT throw — gap-closing path is skipped entirely for BUILD
        CompositeGoal result = planner.resolveCompositeGoal(spec, null);

        assertNotNull(result);
        assertTrue(result.isReachable());

        List<String> ids = new ArrayList<>();
        for (Task t : result.getTaskBatch()) ids.add(t.getId());
        assertTrue("Batch should only contain the terminal (D has no prereqs)", ids.contains("D"));
        assertEquals("Batch should have exactly 1 task", 1, ids.size());
    }

    @Test
    public void build_goal_skips_completed_terminals() {
        // Terminal B requires A; mark A as completed — A should not appear in batch
        PlayerContext ctx = PlayerContext.builder()
                .completedTasks(Collections.singleton("A"))
                .build();

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.BUILD)
                .rawPhrase("build with completed prereq")
                .terminalTaskIds(Collections.singleton("B"))
                .build();

        CompositeGoal result = planner.resolveCompositeGoal(spec, ctx);

        assertNotNull(result);
        assertTrue("Should be reachable", result.isReachable());

        List<String> ids = new ArrayList<>();
        for (Task t : result.getTaskBatch()) ids.add(t.getId());
        assertFalse("Completed task A should not appear in batch", ids.contains("A"));
        assertTrue("Terminal B should still be in batch", ids.contains("B"));
        assertEquals("Batch should contain only B", 1, ids.size());
    }
}
