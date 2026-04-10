package com.leaguesai.agent;

/**
 * What kind of goal the user is asking the planner to resolve. Drives which
 * branch of {@link GoalPlanner} runs and how the result is rendered.
 *
 * <ul>
 *   <li>{@link #RELIC} — unlock a named relic. Resolves to the cost gap plus
 *       a batch of tasks to close it.</li>
 *   <li>{@link #AREA} — unlock a named area. Same resolution path as relic.</li>
 *   <li>{@link #PACT} — informational goal for a pact. Produces no task list
 *       but still fires the callback so the LLM can talk about the pact.</li>
 *   <li>{@link #TASK_BATCH} — "complete all karamja easy", etc. Falls through
 *       to the existing flat {@code resolveGoalTasks} path for backwards
 *       compatibility.</li>
 *   <li>{@link #FREEFORM} — no goal-shape detected; caller should not trigger
 *       the planner at all.</li>
 *   <li>{@link #BUILD} — a gear build goal. Contains a set of terminal task IDs
 *       (the tasks that reward each gear slot's item). The planner builds the
 *       prereq DAG backward from those terminals and returns them topo-sorted.
 *       No gap-closing loop.</li>
 * </ul>
 */
public enum GoalType {
    RELIC,
    AREA,
    PACT,
    TASK_BATCH,
    FREEFORM,
    BUILD   // New: gear build activation — multi-terminal DAG, no gap-closing
}
