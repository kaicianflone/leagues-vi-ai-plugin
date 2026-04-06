package com.leaguesai.agent;

/**
 * One-minute heartbeat that drives the small status label in both the Chat
 * and Goals panels. Two implementations exist:
 *
 * <ul>
 *   <li>{@link LocalHeartbeat} — pure-Java rules over XP / completed-task
 *       deltas. Free, instant, runs every minute.</li>
 *   <li>{@link CoachPulseService} — fires a real LLM call. Used every 5th
 *       minute by the {@code HeartbeatTicker} for a richer message.</li>
 * </ul>
 */
public interface HeartbeatProvider {

    /**
     * Compute the next heartbeat state. Implementations must be cheap and
     * non-blocking when called from the EDT, OR be invoked off-thread by
     * the caller (the LLM-backed provider IS called off-thread).
     */
    HeartbeatState compute(PlayerContext ctx, PlanProgress progress);

    /**
     * Snapshot of the player's plan progress at the moment of the tick.
     * Tracked by {@code HeartbeatTicker} since {@code PlayerContext} doesn't
     * carry per-tick deltas.
     */
    final class PlanProgress {
        public final int totalTasks;
        public final int completedTasks;
        public final int xpGainedSinceLast;
        public final long secondsSinceLastAction;
        public final boolean justCompletedTaskThisTick;

        public PlanProgress(int totalTasks, int completedTasks, int xpGainedSinceLast,
                            long secondsSinceLastAction, boolean justCompletedTaskThisTick) {
            this.totalTasks = totalTasks;
            this.completedTasks = completedTasks;
            this.xpGainedSinceLast = xpGainedSinceLast;
            this.secondsSinceLastAction = secondsSinceLastAction;
            this.justCompletedTaskThisTick = justCompletedTaskThisTick;
        }
    }

    /** Result of a heartbeat tick. The string is rendered as-is into the label. */
    final class HeartbeatState {
        public final String text;

        public HeartbeatState(String text) {
            this.text = text == null ? "" : text;
        }
    }
}
