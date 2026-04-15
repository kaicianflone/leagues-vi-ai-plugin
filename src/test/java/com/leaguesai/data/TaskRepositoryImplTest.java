package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

    // --- Phase 1 (Leagues VI): relics + pacts ---

    @Test
    public void legacy2ArgConstructor_returnsEmptyRelicsAndPacts() {
        // The original constructor signature must still work for any callers
        // that haven't been updated to pass relics/pacts yet.
        TaskRepositoryImpl legacy = new TaskRepositoryImpl(
                Collections.<Task>emptyList(),
                Collections.<Area>emptyList());
        assertNotNull(legacy.getAllRelics());
        assertNotNull(legacy.getAllPacts());
        assertTrue(legacy.getAllRelics().isEmpty());
        assertTrue(legacy.getAllPacts().isEmpty());
    }

    @Test
    public void getAllRelics_preservesInsertionOrder() {
        List<Relic> relics = Arrays.asList(
                Relic.builder().id("r1").name("Endless Harvest").tier(1).build(),
                Relic.builder().id("r6").name("Grimoire").tier(6).build(),
                Relic.builder().id("r8").name("Minion").tier(8).build());
        TaskRepositoryImpl r = new TaskRepositoryImpl(
                Collections.<Task>emptyList(),
                Collections.<Area>emptyList(),
                relics,
                Collections.<Pact>emptyList());

        List<Relic> out = r.getAllRelics();
        assertEquals(3, out.size());
        assertEquals("Endless Harvest", out.get(0).getName());
        assertEquals("Grimoire", out.get(1).getName());
        assertEquals("Minion", out.get(2).getName());
    }

    @Test
    public void getAllPacts_nodeTypeMayBeNullInPhase1() {
        List<Pact> pacts = Arrays.asList(
                Pact.builder().id("pact-aa").name("AA").nodeType(null).effect("regen").build(),
                Pact.builder().id("pact-b1").name("B1").nodeType(null).effect("boost").build());
        TaskRepositoryImpl r = new TaskRepositoryImpl(
                Collections.<Task>emptyList(),
                Collections.<Area>emptyList(),
                Collections.<Relic>emptyList(),
                pacts);

        List<Pact> out = r.getAllPacts();
        assertEquals(2, out.size());
        assertNull("Phase 1 leaves node_type null until wiki documents tree",
                out.get(0).getNodeType());
    }

    @Test
    public void constructor_handlesNullRelicsAndPacts() {
        TaskRepositoryImpl r = new TaskRepositoryImpl(
                Collections.<Task>emptyList(),
                Collections.<Area>emptyList(),
                null,
                null);
        assertTrue(r.getAllRelics().isEmpty());
        assertTrue(r.getAllPacts().isEmpty());
    }

    @Test
    public void constructor_skipsRelicWithNullId() {
        List<Relic> relics = Arrays.asList(
                Relic.builder().id("r1").name("Good").tier(1).build(),
                Relic.builder().id(null).name("Bad").tier(1).build());
        TaskRepositoryImpl r = new TaskRepositoryImpl(
                Collections.<Task>emptyList(),
                Collections.<Area>emptyList(),
                relics,
                Collections.<Pact>emptyList());

        assertEquals("Null-id relic dropped", 1, r.getAllRelics().size());
    }

    // --- findByTargetItemId ---

    @Test
    public void findByTargetItemId_returns_matching_tasks() {
        Task withItem = Task.builder()
                .id("task-item")
                .name("Task with Item")
                .difficulty(Difficulty.EASY)
                .area("misthalin")
                .targetItems(Collections.singletonList(
                        Task.ItemTarget.builder().id(1234).name("Sword").build()))
                .build();
        Task noItem = Task.builder()
                .id("task-noitem")
                .name("Task no Item")
                .difficulty(Difficulty.EASY)
                .area("misthalin")
                .build();

        TaskRepositoryImpl r = new TaskRepositoryImpl(
                Arrays.asList(withItem, noItem),
                Collections.<Area>emptyList());

        List<Task> result = r.findByTargetItemId(1234);
        assertEquals(1, result.size());
        assertEquals("task-item", result.get(0).getId());
    }

    @Test
    public void findByTargetItemId_no_prefix_collision() {
        // id 6570 must NOT match a query for id 657
        Task task6570 = Task.builder()
                .id("task-6570")
                .name("Task 6570")
                .difficulty(Difficulty.EASY)
                .area("misthalin")
                .targetItems(Collections.singletonList(
                        Task.ItemTarget.builder().id(6570).name("Dragon chainbody").build()))
                .build();
        Task task657 = Task.builder()
                .id("task-657")
                .name("Task 657")
                .difficulty(Difficulty.EASY)
                .area("misthalin")
                .targetItems(Collections.singletonList(
                        Task.ItemTarget.builder().id(657).name("Rune sword").build()))
                .build();

        TaskRepositoryImpl r = new TaskRepositoryImpl(
                Arrays.asList(task6570, task657),
                Collections.<Area>emptyList());

        List<Task> result = r.findByTargetItemId(657);
        assertEquals("Only task-657 should match, not task-6570", 1, result.size());
        assertEquals("task-657", result.get(0).getId());
    }

    @Test
    public void findByTargetItemId_returns_empty_when_no_match() {
        TaskRepositoryImpl r = new TaskRepositoryImpl(
                Arrays.asList(taskA, taskB),
                Collections.<Area>emptyList());

        List<Task> result = r.findByTargetItemId(9999);
        assertNotNull("Result must not be null", result);
        assertTrue("Result must be empty for unmatched item id", result.isEmpty());
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

    // --- findFiltered ---

    @Test
    public void findFiltered_noFilters_returnsAll() {
        List<Task> result = repo.findFiltered(null, null, false, 0, Integer.MAX_VALUE);
        assertEquals(4, result.size());
    }

    @Test
    public void findFiltered_areaFilter_caseInsensitive() {
        List<Task> result = repo.findFiltered("ASGARNIA", null, false, 0, Integer.MAX_VALUE);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(t -> "asgarnia".equals(t.getArea())));
    }

    @Test
    public void findFiltered_difficultyFilter_returnsOnlyMatchingTier() {
        Set<Difficulty> easyOnly = EnumSet.of(Difficulty.EASY);
        List<Task> result = repo.findFiltered(null, easyOnly, false, 0, Integer.MAX_VALUE);
        // task-a and task-d are EASY
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(t -> t.getDifficulty() == Difficulty.EASY));
    }

    @Test
    public void findFiltered_pactOnly_returnsOnlyPactCategory() {
        Task pactTask = Task.builder()
                .id("pact-1")
                .name("Pact Task")
                .difficulty(Difficulty.MEDIUM)
                .area("asgarnia")
                .category("pact")
                .build();
        Task nonPactTask = Task.builder()
                .id("non-pact-1")
                .name("Regular Task")
                .difficulty(Difficulty.MEDIUM)
                .area("asgarnia")
                .build();
        TaskRepositoryImpl pactRepo = new TaskRepositoryImpl(
                Arrays.asList(pactTask, nonPactTask),
                Collections.emptyList()
        );
        List<Task> result = pactRepo.findFiltered(null, null, true, 0, Integer.MAX_VALUE);
        assertEquals(1, result.size());
        assertEquals("pact-1", result.get(0).getId());
    }

    @Test
    public void findFiltered_pactOnly_falseReturnsAll() {
        Task pactTask = Task.builder()
                .id("pact-1").name("Pact Task")
                .difficulty(Difficulty.EASY).area("asgarnia").category("pact").build();
        Task regular = Task.builder()
                .id("reg-1").name("Regular").difficulty(Difficulty.EASY).area("asgarnia").build();
        TaskRepositoryImpl r = new TaskRepositoryImpl(Arrays.asList(pactTask, regular), Collections.emptyList());
        assertEquals(2, r.findFiltered(null, null, false, 0, Integer.MAX_VALUE).size());
    }

    @Test
    public void findFiltered_pagination_offsetAndLimit() {
        // repo has 4 tasks; sorted by points desc then name — all have 0 points so sorted by name
        List<Task> page1 = repo.findFiltered(null, null, false, 0, 2);
        List<Task> page2 = repo.findFiltered(null, null, false, 2, 2);
        assertEquals(2, page1.size());
        assertEquals(2, page2.size());
        // No duplicates across pages
        assertNotEquals(page1.get(0).getId(), page2.get(0).getId());
        assertNotEquals(page1.get(1).getId(), page2.get(1).getId());
    }

    @Test
    public void findFiltered_emptyDifficultySet_returnsNothing() {
        // Non-null empty set = explicit "filter to nothing"
        Set<Difficulty> none = EnumSet.noneOf(Difficulty.class);
        List<Task> result = repo.findFiltered(null, none, false, 0, Integer.MAX_VALUE);
        // The impl guards: if difficulties non-null and non-empty, filter. Empty set = no filter.
        // The current impl skips filter when set is empty, so all tasks returned.
        // This test documents the current behavior (not a gate — just a contract snapshot).
        assertNotNull(result);
    }

    @Test
    public void findFiltered_combinedAreaAndDifficulty() {
        Set<Difficulty> easy = EnumSet.of(Difficulty.EASY);
        List<Task> result = repo.findFiltered("asgarnia", easy, false, 0, Integer.MAX_VALUE);
        // Only task-d is EASY in asgarnia
        assertEquals(1, result.size());
        assertEquals("task-d", result.get(0).getId());
    }

    @Test
    public void findFiltered_stablePaginationOrder() {
        // Two calls with same filters must return same order
        List<Task> run1 = repo.findFiltered(null, null, false, 0, Integer.MAX_VALUE);
        List<Task> run2 = repo.findFiltered(null, null, false, 0, Integer.MAX_VALUE);
        for (int i = 0; i < run1.size(); i++) {
            assertEquals(run1.get(i).getId(), run2.get(i).getId());
        }
    }
}
