package com.leaguesai.agent;

import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import net.runelite.api.Skill;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class PromptBuilderTest {

    @Test
    public void testBuildSystemPrompt() {
        Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
        levels.put(Skill.FISHING, 50);
        levels.put(Skill.COOKING, 45);

        Set<String> unlockedAreas = new HashSet<>(Arrays.asList("misthalin", "asgarnia"));
        Set<String> completedTasks = new HashSet<>(Arrays.asList("task_001", "task_002"));

        PlayerContext ctx = PlayerContext.builder()
                .levels(levels)
                .xp(new EnumMap<>(Skill.class))
                .inventory(new HashMap<>())
                .equipment(new HashMap<>())
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
