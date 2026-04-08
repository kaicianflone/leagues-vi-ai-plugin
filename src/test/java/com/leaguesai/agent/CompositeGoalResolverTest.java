package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class CompositeGoalResolverTest {

    private TaskRepository repo;
    private GoalPlanner planner;

    @Before
    public void setUp() {
        repo = mock(TaskRepository.class);
        planner = new GoalPlanner(repo);
        when(repo.getByArea(anyString())).thenReturn(Collections.emptyList());
    }

    private static PlayerContext ctx(int leaguePoints,
                                     Set<String> unlockedAreas,
                                     Map<Skill, Integer> levels) {
        return PlayerContext.builder()
                .levels(levels != null ? levels : Collections.emptyMap())
                .xp(Collections.emptyMap())
                .inventory(Collections.emptyMap())
                .equipment(Collections.emptyMap())
                .completedTasks(Collections.emptySet())
                .unlockedAreas(unlockedAreas != null ? unlockedAreas : Collections.emptySet())
                .location(null)
                .leaguePoints(leaguePoints)
                .combatLevel(50)
                .currentGoal(null)
                .currentPlan(new ArrayList<>())
                .build();
    }

    private static Task task(String id, int points, Difficulty diff, String area) {
        return Task.builder()
                .id(id)
                .name(id)
                .points(points)
                .difficulty(diff)
                .area(area)
                .tasksRequired(Collections.emptyList())
                .build();
    }

    @Test
    public void pactGoalReturnsEmptyReachableBatch() {
        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.PACT)
                .targetId("A1")
                .targetName("Nature's Call")
                .rawPhrase("plan unlock pact Nature's Call")
                .unlockCost(0)
                .build();

        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx(0, null, null));

        assertTrue("pact goals are always reachable", composite.isReachable());
        assertEquals(0, composite.getPointsGap());
        assertTrue("pact goals produce no task batch", composite.getTaskBatch().isEmpty());
        assertEquals(spec, composite.getRoot());
    }

    @Test
    public void unknownCostFallsThroughToTopNAchievableTasks() {
        // Pre-launch window (2026-04-08..2026-04-15) the wiki hasn't
        // published unlock costs for Leagues VI relics/areas, so every goal
        // spec arrives with unlockCost=0. Instead of short-circuiting to an
        // empty plan, the resolver should return the top-N achievable tasks
        // by points-per-effort so the user still gets a useful suggestion.
        // On launch day, real costs arrive and this branch is skipped.
        List<Task> lots = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lots.add(task("T" + i, 10 + i, Difficulty.EASY, "Karamja"));
        }
        when(repo.getAllTasks()).thenReturn(lots);

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.RELIC)
                .targetId("grimoire")
                .targetName("Grimoire")
                .rawPhrase("plan unlock the Grimoire relic")
                .unlockCost(0)  // unknown cost — triggers the heuristic path
                .build();

        PlayerContext ctx = ctx(0, new HashSet<>(Collections.singletonList("Karamja")), null);
        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);

        assertTrue("unknown-cost path must return a non-empty batch", composite.isReachable());
        assertEquals("should cap at 10 tasks regardless of how many are achievable",
                10, composite.getTaskBatch().size());
        assertEquals("unknown-cost mode reports gap as 0 to avoid noisy logs",
                0, composite.getPointsGap());
        assertTrue("coveredBy should still reflect the picked tasks' total points",
                composite.getCoveredBy() > 0);
        assertTrue("no child prereq goals in unknown-cost mode",
                composite.getChildren().isEmpty());
    }

    @Test
    public void unknownCostWithNoAchievableTasksIsUnreachable() {
        // Edge case: unknown cost BUT the player has no achievable tasks
        // (e.g. every task is in a locked area). The resolver should return
        // an empty batch and reachable=false so the UI can tell the user
        // "nothing to suggest" instead of pretending the goal is ready.
        Task locked = task("L", 100, Difficulty.EASY, "Kourend");
        when(repo.getAllTasks()).thenReturn(Collections.singletonList(locked));
        when(repo.getAllAreas()).thenReturn(Collections.emptyList());

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.RELIC)
                .targetId("grimoire")
                .targetName("Grimoire")
                .rawPhrase("plan unlock the Grimoire relic")
                .unlockCost(0)
                .build();

        PlayerContext ctx = ctx(0, new HashSet<>(Collections.singletonList("Varlamore")), null);
        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);
        assertFalse(composite.isReachable());
        assertTrue(composite.getTaskBatch().isEmpty());
    }

    @Test
    public void alreadyAffordableRelicReturnsEmptyBatch() {
        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.RELIC)
                .targetId("grimoire")
                .targetName("Grimoire")
                .rawPhrase("plan unlock the Grimoire relic")
                .unlockCost(200)
                .build();

        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx(500, null, null));
        assertTrue(composite.isReachable());
        assertEquals(0, composite.getPointsGap());
        assertTrue(composite.getTaskBatch().isEmpty());
    }

    @Test
    public void relicGoalGreedyPicksClosesTheGap() {
        // Player has 50 points, needs 200 — 150 point gap.
        // Tasks in Karamja (unlocked): taskA=100/hard, taskB=60/easy, taskC=20/easy, taskD=80/medium
        // Points per effort: A=33.3, B=60, C=20, D=40
        // Greedy order: B (60) → D (80) → A (100) → C (20)
        // Cumulative:    60 → 140 → 240 ≥ 150 (stop). Picks: B, D, A (total 240).
        Task a = task("A", 100, Difficulty.HARD, "Karamja");
        Task b = task("B", 60, Difficulty.EASY, "Karamja");
        Task c = task("C", 20, Difficulty.EASY, "Karamja");
        Task d = task("D", 80, Difficulty.MEDIUM, "Karamja");
        when(repo.getAllTasks()).thenReturn(Arrays.asList(a, b, c, d));

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.RELIC)
                .targetId("grimoire")
                .targetName("Grimoire")
                .rawPhrase("plan unlock the Grimoire relic")
                .unlockCost(200)
                .build();

        PlayerContext ctx = ctx(50, new HashSet<>(Collections.singletonList("Karamja")), null);
        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);

        assertTrue("resolver should close the 150 gap with available tasks",
                composite.isReachable());
        assertEquals(150, composite.getPointsGap());
        assertTrue("covered points should meet or exceed the gap",
                composite.getCoveredBy() >= composite.getPointsGap());
        assertFalse("batch should not be empty", composite.getTaskBatch().isEmpty());
        // Greedy takes high-density first: B (60/1=60) beats the rest.
        assertEquals("B", composite.getTaskBatch().get(0).getId());
    }

    @Test
    public void unreachableGapEmitsAreaUnlockChild() {
        // Player has 0 points, needs 500. Only one achievable task worth 10 in
        // Varlamore. A locked Kourend area has tasks worth 1000 total — the
        // resolver should suggest unlocking it.
        Task varTask = task("V", 10, Difficulty.EASY, "Varlamore");
        when(repo.getAllTasks()).thenReturn(Collections.singletonList(varTask));

        Area varlamore = Area.builder()
                .id("Varlamore")
                .name("Varlamore")
                .unlockCost(0)
                .build();
        Area kourend = Area.builder()
                .id("Kourend")
                .name("Kourend")
                .unlockCost(300)
                .build();
        when(repo.getAllAreas()).thenReturn(Arrays.asList(varlamore, kourend));

        Task kourendTaskBig = task("K1", 600, Difficulty.HARD, "Kourend");
        Task kourendTaskSmall = task("K2", 400, Difficulty.EASY, "Kourend");
        when(repo.getByArea("Kourend"))
                .thenReturn(Arrays.asList(kourendTaskBig, kourendTaskSmall));
        when(repo.getByArea("Varlamore")).thenReturn(Collections.singletonList(varTask));

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.RELIC)
                .targetId("grimoire")
                .targetName("Grimoire")
                .rawPhrase("plan unlock the Grimoire relic")
                .unlockCost(500)
                .build();

        PlayerContext ctx = ctx(0, new HashSet<>(Collections.singletonList("Varlamore")), null);
        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);

        assertFalse("gap is too big to close with Varlamore alone",
                composite.isReachable());
        assertEquals(500, composite.getPointsGap());
        assertEquals("should suggest one area unlock child", 1, composite.getChildren().size());
        assertEquals("Kourend", composite.getChildren().get(0).getTargetName());
        assertEquals(GoalType.AREA, composite.getChildren().get(0).getType());
    }

    @Test
    public void completedTasksAreExcluded() {
        Task a = task("A", 200, Difficulty.EASY, "Karamja");
        Task b = task("B", 200, Difficulty.EASY, "Karamja");
        when(repo.getAllTasks()).thenReturn(Arrays.asList(a, b));

        // Mark task A as already completed — resolver must skip it.
        PlayerContext ctx = PlayerContext.builder()
                .levels(Collections.emptyMap())
                .xp(Collections.emptyMap())
                .inventory(Collections.emptyMap())
                .equipment(Collections.emptyMap())
                .completedTasks(new HashSet<>(Collections.singletonList("A")))
                .unlockedAreas(new HashSet<>(Collections.singletonList("Karamja")))
                .location(null)
                .leaguePoints(0)
                .combatLevel(50)
                .currentPlan(new ArrayList<>())
                .build();

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.RELIC)
                .targetId("grimoire")
                .targetName("Grimoire")
                .rawPhrase("plan unlock the Grimoire relic")
                .unlockCost(150)
                .build();

        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);
        assertTrue(composite.isReachable());
        assertEquals(1, composite.getTaskBatch().size());
        assertEquals("B", composite.getTaskBatch().get(0).getId());
    }

    @Test
    public void lockedAreaTasksAreExcluded() {
        Task k = task("K", 500, Difficulty.EASY, "Kourend");  // locked
        Task v = task("V", 100, Difficulty.EASY, "Varlamore");  // unlocked
        when(repo.getAllTasks()).thenReturn(Arrays.asList(k, v));
        when(repo.getAllAreas()).thenReturn(Collections.emptyList());

        PlayerContext ctx = ctx(0, new HashSet<>(Collections.singletonList("Varlamore")), null);

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.RELIC)
                .targetId("grimoire")
                .targetName("Grimoire")
                .rawPhrase("plan unlock the Grimoire relic")
                .unlockCost(50)
                .build();

        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);
        assertEquals("locked-area task K must be excluded", 1, composite.getTaskBatch().size());
        assertEquals("V", composite.getTaskBatch().get(0).getId());
    }

    @Test
    public void skillRequirementsAreEnforced() {
        // Task A requires Fishing 60; player has 50. Must be excluded even if
        // in an unlocked area.
        Map<String, Integer> reqs = new HashMap<>();
        reqs.put("FISHING", 60);
        Task a = Task.builder()
                .id("A")
                .name("Big Fish")
                .points(200)
                .difficulty(Difficulty.EASY)
                .area("Karamja")
                .skillsRequired(reqs)
                .tasksRequired(Collections.emptyList())
                .build();
        Task b = task("B", 100, Difficulty.EASY, "Karamja");
        when(repo.getAllTasks()).thenReturn(Arrays.asList(a, b));

        Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
        levels.put(Skill.FISHING, 50);
        PlayerContext ctx = ctx(0, new HashSet<>(Collections.singletonList("Karamja")), levels);

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.RELIC)
                .targetId("grimoire")
                .targetName("Grimoire")
                .rawPhrase("plan unlock the Grimoire relic")
                .unlockCost(50)
                .build();

        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);
        assertEquals(1, composite.getTaskBatch().size());
        assertEquals("B", composite.getTaskBatch().get(0).getId());
    }

    @Test
    public void unknownSkillNameExcludesTheTask() {
        // Defense in depth: if a stale DB row somehow has a skill key that
        // doesn't map to the RuneLite Skill enum (e.g. a pre-alias scraper
        // wrote "runecrafting" instead of "runecraft"), skillsMet must
        // fail-closed and exclude the task rather than silently skip the
        // requirement. Over-recommending is worse than under-recommending.
        Map<String, Integer> reqs = new HashMap<>();
        reqs.put("runecrafting", 80);  // bogus key — no RuneLite enum match
        Task a = Task.builder()
                .id("A")
                .name("Ghost Task")
                .points(200)
                .difficulty(Difficulty.EASY)
                .area("Karamja")
                .skillsRequired(reqs)
                .tasksRequired(Collections.emptyList())
                .build();
        Task b = task("B", 100, Difficulty.EASY, "Karamja");
        when(repo.getAllTasks()).thenReturn(Arrays.asList(a, b));

        PlayerContext ctx = ctx(0, new HashSet<>(Collections.singletonList("Karamja")), null);

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.RELIC)
                .targetId("grimoire")
                .targetName("Grimoire")
                .rawPhrase("plan unlock the Grimoire relic")
                .unlockCost(50)
                .build();

        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);
        assertEquals("unknown skill name must exclude the task (fail-closed)",
                1, composite.getTaskBatch().size());
        assertEquals("B", composite.getTaskBatch().get(0).getId());
    }

    @Test
    public void skillRequirementsAreEnforcedWithLowercaseKeys() {
        // Production data shape: the scraper's TaskNormalizer stores skill
        // keys lowercased ("fishing" -> 60), not uppercased. This test guards
        // the same filter logic against the real storage convention so a
        // future refactor of skillsMet's case handling can't silently break.
        Map<String, Integer> reqs = new HashMap<>();
        reqs.put("fishing", 60);
        Task a = Task.builder()
                .id("A")
                .name("Big Fish")
                .points(200)
                .difficulty(Difficulty.EASY)
                .area("Karamja")
                .skillsRequired(reqs)
                .tasksRequired(Collections.emptyList())
                .build();
        Task b = task("B", 100, Difficulty.EASY, "Karamja");
        when(repo.getAllTasks()).thenReturn(Arrays.asList(a, b));

        Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
        levels.put(Skill.FISHING, 50);
        PlayerContext ctx = ctx(0, new HashSet<>(Collections.singletonList("Karamja")), levels);

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.RELIC)
                .targetId("grimoire")
                .targetName("Grimoire")
                .rawPhrase("plan unlock the Grimoire relic")
                .unlockCost(50)
                .build();

        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);
        assertEquals("lowercase scraper key should still filter the task out",
                1, composite.getTaskBatch().size());
        assertEquals("B", composite.getTaskBatch().get(0).getId());
    }
}
