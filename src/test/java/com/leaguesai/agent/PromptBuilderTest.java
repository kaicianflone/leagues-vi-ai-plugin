package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;
import net.runelite.api.Skill;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PromptBuilderTest {

    @Test
    public void testBuildSystemPrompt() {
        Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
        levels.put(Skill.FISHING, 50);
        levels.put(Skill.COOKING, 45);

        Set<String> unlockedAreas = new HashSet<>(Arrays.asList("misthalin", "asgarnia"));
        Set<String> completedTasks = new HashSet<>(Arrays.asList("task_001", "task_002"));

        Map<String, Integer> inventory = new LinkedHashMap<>();
        inventory.put("Lobster", 5);
        inventory.put("Tuna", 3);

        Map<String, Integer> equipment = new LinkedHashMap<>();
        equipment.put("Whip", 1);

        PlayerContext ctx = PlayerContext.builder()
                .levels(levels)
                .xp(new EnumMap<>(Skill.class))
                .inventory(inventory)
                .equipment(equipment)
                .completedTasks(completedTasks)
                .unlockedAreas(unlockedAreas)
                .location(null)
                .leaguePoints(150)
                .combatLevel(50)
                .currentGoal("Complete all easy tasks in Misthalin")
                .currentPlan(new ArrayList<>())
                .build();

        String prompt = PromptBuilder.buildSystemPrompt(ctx);

        assertNotNull(prompt);
        assertTrue("Should contain FISHING level", prompt.contains("FISHING: 50"));
        assertTrue("Should contain COOKING level", prompt.contains("COOKING: 45"));
        assertTrue("Should contain misthalin area", prompt.toLowerCase().contains("misthalin"));
        assertTrue("Should contain league points", prompt.contains("150"));
        assertTrue("Should contain current goal", prompt.contains("Complete all easy tasks in Misthalin"));
        assertTrue("Should contain Inventory section header", prompt.contains("## Inventory"));
        assertTrue("Should contain Lobster with quantity", prompt.contains("Lobster x5"));
        assertTrue("Should contain Tuna with quantity", prompt.contains("Tuna x3"));
        assertTrue("Should contain Equipment section header", prompt.contains("## Equipment"));
        assertTrue("Should contain Whip in equipment", prompt.contains("- Whip"));
    }

    @Test
    public void testBuildSystemPrompt_emptyInventory_rendersEmptyMarker() {
        PlayerContext ctx = PlayerContext.builder()
                .levels(new EnumMap<>(Skill.class))
                .xp(new EnumMap<>(Skill.class))
                .inventory(new LinkedHashMap<>())
                .equipment(new LinkedHashMap<>())
                .completedTasks(new HashSet<>())
                .unlockedAreas(new HashSet<>())
                .location(null)
                .leaguePoints(0)
                .combatLevel(3)
                .currentGoal("")
                .currentPlan(new ArrayList<>())
                .build();

        String prompt = PromptBuilder.buildSystemPrompt(ctx);
        assertTrue("Should contain Inventory header", prompt.contains("## Inventory"));
        assertTrue("Should render (empty) for empty inventory",
                prompt.contains("## Inventory\n- (empty)"));
        assertTrue("Should render (empty) for empty equipment",
                prompt.contains("## Equipment\n- (empty)"));
    }

    @Test
    public void testBuildSystemPrompt_withRelevantTasks_includesSection() {
        PlayerContext ctx = PlayerContext.builder()
                .levels(new EnumMap<>(Skill.class))
                .xp(new EnumMap<>(Skill.class))
                .inventory(new HashMap<>())
                .equipment(new HashMap<>())
                .completedTasks(new HashSet<>())
                .unlockedAreas(new HashSet<>())
                .location(null)
                .leaguePoints(0)
                .combatLevel(3)
                .currentGoal("")
                .currentPlan(new ArrayList<>())
                .build();

        Task task = Task.builder()
                .id("task_shrimp")
                .name("Catch Shrimp")
                .description("Catch a raw shrimp from the Lumbridge fishing spot.")
                .difficulty(Difficulty.EASY)
                .points(10)
                .area("misthalin")
                .build();

        String prompt = PromptBuilder.buildSystemPrompt(ctx, Collections.singletonList(task));

        assertNotNull(prompt);
        assertTrue("Should contain Relevant Tasks section header",
                prompt.contains("## Relevant Tasks"));
        assertTrue("Should contain task name", prompt.contains("Catch Shrimp"));
        assertTrue("Should contain task id", prompt.contains("task_shrimp"));
        assertTrue("Should contain description", prompt.contains("Lumbridge fishing"));
    }

    @Test
    public void testBuildSystemPrompt_emptyRelevantTasks_omitsSection() {
        PlayerContext ctx = PlayerContext.builder()
                .levels(new EnumMap<>(Skill.class))
                .xp(new EnumMap<>(Skill.class))
                .inventory(new HashMap<>())
                .equipment(new HashMap<>())
                .completedTasks(new HashSet<>())
                .unlockedAreas(new HashSet<>())
                .location(null)
                .leaguePoints(0)
                .combatLevel(3)
                .currentGoal("")
                .currentPlan(new ArrayList<>())
                .build();

        String prompt = PromptBuilder.buildSystemPrompt(ctx, Collections.emptyList());
        assertFalse("Should NOT contain Relevant Tasks header when empty",
                prompt.contains("## Relevant Tasks"));
    }

    private static PlayerContext emptyCtx() {
        return PlayerContext.builder()
                .levels(new EnumMap<>(Skill.class))
                .xp(new EnumMap<>(Skill.class))
                .inventory(new HashMap<>())
                .equipment(new HashMap<>())
                .completedTasks(new HashSet<>())
                .unlockedAreas(new HashSet<>(Collections.singletonList("Karamja")))
                .location(null)
                .leaguePoints(0)
                .combatLevel(3)
                .currentGoal("")
                .currentPlan(new ArrayList<>())
                .build();
    }

    @Test
    public void testBuildSystemPrompt_withRepo_includesRelicsSection() {
        TaskRepository repo = mock(TaskRepository.class);
        Relic grimoire = Relic.builder()
                .id("grimoire")
                .name("Grimoire")
                .tier(1)
                .unlockCost(200)
                .description("Auto-picks herbs for you.")
                .build();
        when(repo.getAllRelics()).thenReturn(Collections.singletonList(grimoire));
        when(repo.getAllAreas()).thenReturn(Collections.emptyList());
        when(repo.getAllPacts()).thenReturn(Collections.emptyList());

        String prompt = PromptBuilder.buildSystemPrompt(emptyCtx(), Collections.emptyList(), repo);
        assertTrue("should contain Relics header", prompt.contains("## Relics"));
        assertTrue("should contain relic name", prompt.contains("Grimoire"));
        assertTrue("should contain tier", prompt.contains("Tier 1"));
        assertTrue("should contain cost", prompt.contains("200 pts"));
        assertTrue("should contain description", prompt.contains("Auto-picks herbs"));
    }

    @Test
    public void testBuildSystemPrompt_withRepo_includesAreasSection() {
        TaskRepository repo = mock(TaskRepository.class);
        Area karamja = Area.builder().id("Karamja").name("Karamja").unlockCost(0).build();
        Area kourend = Area.builder().id("Kourend").name("Kourend").unlockCost(300).build();
        when(repo.getAllRelics()).thenReturn(Collections.emptyList());
        when(repo.getAllAreas()).thenReturn(Arrays.asList(karamja, kourend));
        when(repo.getAllPacts()).thenReturn(Collections.emptyList());

        String prompt = PromptBuilder.buildSystemPrompt(emptyCtx(), Collections.emptyList(), repo);
        assertTrue("should contain Areas header", prompt.contains("## Areas"));
        assertTrue("Karamja is in unlockedAreas, should render UNLOCKED",
                prompt.contains("Karamja") && prompt.contains("UNLOCKED"));
        assertTrue("Kourend is locked", prompt.contains("Kourend"));
        assertTrue("zero-cost areas render as TBD", prompt.contains("cost TBD"));
        assertTrue("300-cost area renders with pts", prompt.contains("300 pts"));
    }

    @Test
    public void testBuildSystemPrompt_withRepo_includesPactsSectionAndBudget() {
        TaskRepository repo = mock(TaskRepository.class);
        Pact p = Pact.builder()
                .id("A1")
                .name("Nature's Call")
                .effect("Nature runes regenerate 50% of the time")
                .build();
        when(repo.getAllRelics()).thenReturn(Collections.emptyList());
        when(repo.getAllAreas()).thenReturn(Collections.emptyList());
        when(repo.getAllPacts()).thenReturn(Collections.singletonList(p));

        String prompt = PromptBuilder.buildSystemPrompt(emptyCtx(), Collections.emptyList(), repo);
        assertTrue("should contain Pacts header", prompt.contains("## Demonic Pacts"));
        assertTrue("should contain the 40-pact budget sentence",
                prompt.contains("up to **40 pacts total**"));
        assertTrue("should contain the 3-respec sentence",
                prompt.contains("**3 full respecs**"));
        assertTrue("should contain pact name", prompt.contains("Nature's Call"));
        assertTrue("should contain pact effect", prompt.contains("Nature runes regenerate"));
    }

    @Test
    public void testBuildSystemPrompt_nullRepo_omitsUnlockablesSections() {
        String prompt = PromptBuilder.buildSystemPrompt(emptyCtx(), Collections.emptyList(), null);
        assertFalse("null repo should omit Relics section", prompt.contains("## Relics"));
        assertFalse("null repo should omit Areas section", prompt.contains("## Areas"));
        assertFalse("null repo should omit Pacts section", prompt.contains("## Demonic Pacts"));
    }

    @Test
    public void testBuildPlanningPrompt() {
        Task task = Task.builder()
                .id("task_shrimp")
                .name("Catch Shrimp")
                .difficulty(Difficulty.EASY)
                .points(10)
                .area("misthalin")
                .build();

        List<Task> candidateTasks = Collections.singletonList(task);
        String goal = "unlock karamja";

        String prompt = PromptBuilder.buildPlanningPrompt(goal, candidateTasks);

        assertNotNull(prompt);
        assertTrue("Should contain the goal", prompt.contains("unlock karamja"));
        assertTrue("Should contain task name", prompt.contains("Catch Shrimp"));
    }
}
