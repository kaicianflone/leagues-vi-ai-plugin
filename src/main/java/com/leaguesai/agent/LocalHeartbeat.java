package com.leaguesai.agent;

/**
 * Pure-Java heartbeat rules. Zero network. Decides between a small set of
 * canned encouragements based on XP and task deltas captured by the ticker.
 *
 * <p>Order matters — the first matching rule wins.
 */
public class LocalHeartbeat implements HeartbeatProvider {

    @Override
    public HeartbeatState compute(PlayerContext ctx, PlanProgress p) {
        if (ctx == null || p == null) {
            return new HeartbeatState("\uD83D\uDC4D Looking good");
        }

        // MILESTONE: just finished a task this tick
        if (p.justCompletedTaskThisTick) {
            return new HeartbeatState("\u2728 Task done — nice. Onto the next one.");
        }

        // ON_PACE: gained XP and made plan progress
        if (p.xpGainedSinceLast > 0 && p.totalTasks > 0 && p.completedTasks > 0) {
            return new HeartbeatState("\uD83D\uDC4D On pace — keep going");
        }

        // ALMOST_THERE: gained XP but no task completion yet
        if (p.xpGainedSinceLast > 0) {
            return new HeartbeatState("\uD83D\uDD25 You're grinding — almost there");
        }

        // IDLE: no XP, no recent action
        if (p.xpGainedSinceLast == 0 && p.secondsSinceLastAction >= 120) {
            return new HeartbeatState("\u23F8\uFE0F Quick break? Pick a fast task to reset the rhythm.");
        }

        // BEHIND_PACE: have a plan, no progress yet
        if (p.totalTasks > 0 && p.completedTasks == 0) {
            return new HeartbeatState("\uD83D\uDCAA Plan loaded — start with task #1.");
        }

        // Default
        return new HeartbeatState("\uD83D\uDC4D Looking good");
    }
}
