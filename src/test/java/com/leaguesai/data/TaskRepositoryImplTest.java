package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class TaskRepositoryImplTest {

    // Tasks:
    //   task-a (EASY, area "misthalin", no prereqs)
    //   task-b (MEDIUM, area "misthalin", prereqs: [task-a])
    //   task-c (HARD, area "asgarnia", prereqs: [task-b])
    //   task-d (EASY, area "asgarnia", prereqs: [task-a, task-b])

    private Task taskA;
    private Task taskB;
    private Task taskC;
    private Task taskD;

    private Area areaMisthalin;
    private Area areaAsgarnia;

    private TaskRepositoryImpl repo;

    @Before
    public void setUp() {
        taskA = Task.builder()
                .id("task-a")
                .name("Task A")
                .difficulty(Difficulty.EASY)
                .area("misthalin")
                .tasksRequired(Collections.emptyList())
                .build();

        taskB = Task.builder()
                .id("task-b")
                .name("Task B")
                .difficulty(Difficulty.MEDIUM)
                .area("misthalin")
                .tasksRequired(Collections.singletonList("task-a"))
                .build();

        taskC = Task.builder()
                .id("task-c")
                .name("Task C")
                .difficulty(Difficulty.HARD)
                .area("asgarnia")
                .tasksRequired(Collections.singletonList("task-b"))
                .build();

        taskD = Task.builder()
                .id("task-d")
                .name("Task D")
                .difficulty(Difficulty.EASY)
                .area("asgarnia")
                .tasksRequired(Arrays.asList("task-a", "task-b"))
                .build();

        areaMisthalin = Area.builder()
                .id("area-misthalin")
                .name("Misthalin")
                .unlockCost(0)
                .regionIds(Arrays.asList(12850, 12851))
                .build();

        areaAsgarnia = Area.builder()
                .id("area-asgarnia")
                .name("Asgarnia")
                .unlockCost(500)
                .regionIds(Arrays.asList(11828, 11829))
                .build();

        repo = new TaskRepositoryImpl(
                Arrays.asList(taskA, taskB, taskC, taskD),
                Arrays.asList(areaMisthalin, areaAsgarnia)
        );
    }

    // --- getAllTasks ---

    @Test
    public void getAllTasks_returnsAllFourTasks() {
        List<Task> all = repo.getAllTasks();
        assertEquals(4, all.size());
    }

    // --- getById ---

    @Test
    public void getById_found() {
        Task result = repo.getById("task-a");
        assertNotNull(result);
        assertEquals("task-a", result.getId());
        assertEquals("Task A", result.getName());
    }

    @Test
    public void getById_missing_returnsNull() {
        assertNull(repo.getById("no-such-task"));
    }

    @Test
    public void getById_null_returnsNull() {
        assertNull(repo.getById(null));
    }

    // --- getByArea ---

    @Test
    public void getByArea_misthalin_returnsTwoTasks() {
        List<Task> result = repo.getByArea("misthalin");
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(t -> "misthalin".equals(t.getArea())));
    }

    @Test
    public void getByArea_asgarnia_returnsTwoTasks() {
        List<Task> result = repo.getByArea("asgarnia");
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(t -> "asgarnia".equals(t.getArea())));
    }

    @Test
    public void getByArea_unknown_returnsEmpty() {
        List<Task> result = repo.getByArea("karamja");
        assertTrue(result.isEmpty());
    }

    // --- getByDifficulty ---

    @Test
    public void getByDifficulty_easy_returnsTwoTasks() {
        List<Task> result = repo.getByDifficulty(Difficulty.EASY);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(t -> Difficulty.EASY.equals(t.getDifficulty())));
    }

    @Test
    public void getByDifficulty_hard_returnsOneTask() {
        List<Task> result = repo.getByDifficulty(Difficulty.HARD);
        assertEquals(1, result.size());
        assertEquals("task-c", result.get(0).getId());
    }

    @Test
    public void getByDifficulty_elite_returnsEmpty() {
        List<Task> result = repo.getByDifficulty(Difficulty.ELITE);
        assertTrue(result.isEmpty());
    }

    // --- getPrerequisites (direct only) ---

    @Test
    public void getPrerequisites_taskWithNone_returnsEmpty() {
        List<Task> prereqs = repo.getPrerequisites("task-a");
        assertTrue(prereqs.isEmpty());
    }

    @Test
    public void getPrerequisites_taskBHasOnePrereq() {
        List<Task> prereqs = repo.getPrerequisites("task-b");
        assertEquals(1, prereqs.size());
        assertEquals("task-a", prereqs.get(0).getId());
    }

    @Test
    public void getPrerequisites_taskDHasTwoPrereqs() {
        List<Task> prereqs = repo.getPrerequisites("task-d");
        assertEquals(2, prereqs.size());
        assertTrue(prereqs.stream().anyMatch(t -> "task-a".equals(t.getId())));
        assertTrue(prereqs.stream().anyMatch(t -> "task-b".equals(t.getId())));
    }

    @Test
    public void getPrerequisites_unknownTask_returnsEmpty() {
        List<Task> prereqs = repo.getPrerequisites("no-such-task");
        assertTrue(prereqs.isEmpty());
    }

    // --- getAllPrerequisites (recursive) ---

    @Test
    public void getAllPrerequisites_taskA_noPrereqs_returnsEmpty() {
        List<Task> all = repo.getAllPrerequisites("task-a");
        assertTrue(all.isEmpty());
    }

    @Test
    public void getAllPrerequisites_taskB_returnsJustA() {
        List<Task> all = repo.getAllPrerequisites("task-b");
        assertEquals(1, all.size());
        assertEquals("task-a", all.get(0).getId());
    }

    @Test
    public void getAllPrerequisites_taskC_returnsBothAandB() {
        // task-c -> task-b -> task-a, so recursive result should contain both
        List<Task> all = repo.getAllPrerequisites("task-c");
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(t -> "task-a".equals(t.getId())));
        assertTrue(all.stream().anyMatch(t -> "task-b".equals(t.getId())));
    }

    @Test
    public void getAllPrerequisites_taskD_noDuplicates() {
        // task-d -> [task-a, task-b]; task-b -> task-a
        // task-a must appear only once
        List<Task> all = repo.getAllPrerequisites("task-d");
        assertEquals(2, all.size());
        long countA = all.stream().filter(t -> "task-a".equals(t.getId())).count();
        assertEquals("task-a should appear exactly once", 1, countA);
        assertTrue(all.stream().anyMatch(t -> "task-b".equals(t.getId())));
    }

    @Test
    public void getAllPrerequisites_unknownTask_returnsEmpty() {
        List<Task> all = repo.getAllPrerequisites("no-such-task");
        assertTrue(all.isEmpty());
    }

    // --- getAllAreas ---

    @Test
    public void getAllAreas_returnsBothAreas() {
        List<Area> areas = repo.getAllAreas();
        assertEquals(2, areas.size());
    }

    // --- getAreaByRegionId ---

    @Test
    public void getAreaByRegionId_found() {
        Area area = repo.getAreaByRegionId(12850);
        assertNotNull(area);
        assertEquals("area-misthalin", area.getId());
    }

    @Test
    public void getAreaByRegionId_secondRegion() {
        Area area = repo.getAreaByRegionId(11829);
        assertNotNull(area);
        assertEquals("area-asgarnia", area.getId());
    }

    @Test
    public void getAreaByRegionId_notFound_returnsNull() {
        assertNull(repo.getAreaByRegionId(99999));
    }

    // --- Cycle safety test: task with self-referencing prereq ---

    @Test
    public void getAllPrerequisites_cycleIsSafe() {
        // Build a cycle: x -> y -> x
        Task x = Task.builder()
                .id("x")
                .name("X")
                .difficulty(Difficulty.EASY)
                .area("test")
                .tasksRequired(Collections.singletonList("y"))
                .build();
        Task y = Task.builder()
                .id("y")
                .name("Y")
                .difficulty(Difficulty.EASY)
                .area("test")
                .tasksRequired(Collections.singletonList("x"))
                .build();

        TaskRepositoryImpl cycleRepo = new TaskRepositoryImpl(
                Arrays.asList(x, y),
                Collections.emptyList()
        );

        // Should terminate without StackOverflow and return exactly the other task
        List<Task> all = cycleRepo.getAllPrerequisites("x");
        assertEquals(1, all.size());
        assertEquals("y", all.get(0).getId());
    }
}
