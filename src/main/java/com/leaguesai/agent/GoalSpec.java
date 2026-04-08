package com.leaguesai.agent;

import lombok.Builder;
import lombok.Value;

/**
 * A parsed, repository-resolved goal. Produced by {@link GoalSpecParser} from
 * the user's chat message or an "Set as goal" click in {@code UnlockablesPanel},
 * consumed by {@link GoalPlanner#resolveCompositeGoal}.
 *
 * <p>{@code targetId} is {@code null} for {@link GoalType#TASK_BATCH} and
 * {@link GoalType#FREEFORM}. It's populated for RELIC / AREA / PACT goals by
 * looking up the target name via {@code TaskRepository.findRelicByName} etc.
 *
 * <p>{@code targetName} is the display name (e.g. "Grimoire"). {@code rawPhrase}
 * is the original user message, preserved so downstream code can keep using
 * it as the goal banner text in the UI.
 *
 * <p>{@code unlockCost} is copied from the target's data row at parse time so
 * the planner doesn't need to re-fetch it. {@code 0} for PACT (no cost) and
 * for targets with a {@code null} / missing cost on the wiki.
 */
@Value
@Builder
public class GoalSpec {
    GoalType type;
    String targetId;
    String targetName;
    String rawPhrase;
    int unlockCost;
}
