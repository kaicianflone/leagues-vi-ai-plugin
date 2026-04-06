package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import net.runelite.api.Skill;

import java.util.List;
import java.util.Map;

public class PromptBuilder {

    // Utility class — no instances
    private PromptBuilder() {}

    /**
     * Builds a Markdown system prompt summarising the player's current state.
     */
    public static String buildSystemPrompt(PlayerContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert OSRS Leagues VI (Demonic Pacts) coach.\n\n");

        // Player State
        sb.append("## Player State\n");
        sb.append("- Combat Level: ").append(ctx.getCombatLevel()).append("\n");
        sb.append("- League Points: ").append(ctx.getLeaguePoints()).append("\n");
        if (ctx.getLocation() != null) {
            sb.append("- Location: ").append(ctx.getLocation().toString()).append("\n");
        }
        sb.append("\n");

        // Skills
        sb.append("## Skills\n");
        if (ctx.getLevels() != null && !ctx.getLevels().isEmpty()) {
            for (Map.Entry<Skill, Integer> entry : ctx.getLevels().entrySet()) {
                sb.append("- ").append(entry.getKey().name()).append(": ")
                        .append(entry.getValue()).append("\n");
            }
        }
        sb.append("\n");

        // Unlocked Areas
        sb.append("## Unlocked Areas\n");
        if (ctx.getUnlockedAreas() != null && !ctx.getUnlockedAreas().isEmpty()) {
            for (String area : ctx.getUnlockedAreas()) {
                sb.append("- ").append(area).append("\n");
            }
        } else {
            sb.append("- None\n");
        }
        sb.append("\n");

        // Completed Tasks
        int completedCount = ctx.getCompletedTasks() != null ? ctx.getCompletedTasks().size() : 0;
        sb.append("## Completed Tasks: ").append(completedCount).append("\n\n");

        // Current Goal
        sb.append("## Current Goal\n");
        sb.append(ctx.getCurrentGoal() != null ? ctx.getCurrentGoal() : "None").append("\n\n");

        // Current Plan (next 5 steps)
        sb.append("## Current Plan (next 5 steps)\n");
        List<PlannedStep> plan = ctx.getCurrentPlan();
        if (plan != null && !plan.isEmpty()) {
            int limit = Math.min(5, plan.size());
            for (int i = 0; i < limit; i++) {
                PlannedStep step = plan.get(i);
                String instruction = step.getInstruction() != null ? step.getInstruction() : "(no instruction)";
                sb.append(i + 1).append(". ").append(instruction).append("\n");
            }
        } else {
            sb.append("- No steps planned yet.\n");
        }

        return sb.toString();
    }

    /**
     * Builds a planning prompt asking the model to sequence tasks toward a goal.
     */
    public static String buildPlanningPrompt(String goal, List<Task> candidateTasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plan a task sequence to achieve this goal: ").append(goal).append("\n\n");
        sb.append("Available tasks:\n");
        if (candidateTasks != null) {
            for (Task task : candidateTasks) {
                sb.append("- ").append(task.getName())
                        .append(" [").append(task.getDifficulty())
                        .append(", ").append(task.getPoints()).append("pts")
                        .append(", ").append(task.getArea()).append("]")
                        .append(" (id: ").append(task.getId()).append(")")
                        .append("\n");
            }
        }
        sb.append("\n");
        sb.append("Instructions:\n");
        sb.append("- Optimize for proximity (prefer nearby tasks to minimise travel)\n");
        sb.append("- Prefer lower difficulty tiers first to unlock rewards early\n");
        sb.append("- Batch tasks that train the same skill together\n");
        sb.append("- Return a JSON array of task IDs in the recommended order, e.g. [\"id1\",\"id2\"]\n");
        return sb.toString();
    }

    /**
     * Static advice prompt — instructs the model to give concise next-step advice.
     */
    public static String buildAdvicePrompt() {
        return "Given the player's current state, goal, and plan progress, what should they focus on next "
                + "and why? Be concise (2-3 paragraphs). Consider: efficiency, upcoming unlocks, "
                + "skill milestones, and any opportunities to batch nearby tasks.";
    }
}
