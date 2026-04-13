package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A resolved composite goal: one high-level target (relic / area / pact) plus
 * the ordered prerequisite chain the planner decided the player has to walk
 * to reach it.
 *
 * <p>Produced by {@link GoalPlanner#resolveCompositeGoal}. Consumed by
 * {@code ChatService.maybeTriggerPlanner}, which turns {@link #taskBatch}
 * into the flat {@code List<PlannedStep>} the UI and overlays already know
 * how to render.
 *
 * <p>Shape for a typical relic goal:
 * <pre>
 *   root     = RELIC Grimoire (cost 200)
 *   children = [AREA Kourend (prereq to reach Kourend tasks)]
 *   taskBatch = [task1, task2, ..., taskN]  // enough to close the point gap
 *   pointsGap = 150    // current points = 50, needed = 200
 *   coveredBy = 160    // total points from the task batch
 * </pre>
 *
 * <p>For {@link GoalType#PACT} goals the task batch is empty — pacts have no
 * point cost, so the resolver just echoes the goal back with an empty batch.
 * The UI still shows the pact name and the LLM can explain it via the
 * prompt context sections.
 *
 * <p>{@link #reachable} is false when the resolver couldn't find any set of
 * achievable tasks that would close the point gap. The UI surfaces this so
 * the user knows they need to unlock an area or train a skill first.
 */
@Value
@Builder
public class CompositeGoal {
    GoalSpec root;

    /** Prerequisite sub-goals (e.g. "unlock Kourend" before this relic). */
    @Builder.Default
    List<GoalSpec> children = Collections.emptyList();

    /** Ordered task batch to execute. May be empty for PACT goals. */
    @Builder.Default
    List<Task> taskBatch = Collections.emptyList();

    /**
     * Pre-built steps for items resolved via {@link WikiItemLookup} — bypasses
     * Task-based pipeline when the item has no dependency chain (shop items,
     * NPC trades, etc.). Empty unless the ITEM goal resolved via wiki lookup.
     */
    @Builder.Default
    List<PlannedStep> directSteps = Collections.emptyList();

    /**
     * Skilling steps from the OSRS skill calculator — present when an item is
     * obtained by training a skill (smithing, crafting, fletching, etc.). Stored
     * here so {@code ChatService} can surface them in the plan without creating
     * synthetic Task objects.
     */
    @Builder.Default
    List<PlannedStep> skillingSteps = Collections.emptyList();

    int pointsGap;
    int coveredBy;
    boolean reachable;
}
