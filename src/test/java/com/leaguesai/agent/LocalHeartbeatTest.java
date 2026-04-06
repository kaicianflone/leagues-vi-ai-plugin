package com.leaguesai.agent;

import com.leaguesai.agent.HeartbeatProvider.HeartbeatState;
import com.leaguesai.agent.HeartbeatProvider.PlanProgress;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure rule tests for {@link LocalHeartbeat}. No mocks, no I/O.
 *
 * <p>Each test feeds a synthetic context+progress and asserts the rule
 * that should fire by checking for a substring of the canned message.
 * The substring strategy keeps the test stable when copy is tweaked.
 */
public class LocalHeartbeatTest {

    private LocalHeartbeat heartbeat;
    private PlayerContext ctx;

    @Before
    public void setUp() {
        heartbeat = new LocalHeartbeat();
        ctx = PlayerContext.builder()
                .levels(new EnumMap<>(Skill.class))
                .xp(new EnumMap<>(Skill.class))
                .inventory(new HashMap<>())
                .equipment(new HashMap<>())
                .completedTasks(new HashSet<>())
                .unlockedAreas(new HashSet<>())
                .leaguePoints(0)
                .combatLevel(3)
                .currentGoal("")
                .currentPlan(new ArrayList<>())
                .build();
    }

    @Test
    public void milestoneRule_firesWhenTaskJustCompleted() {
        PlanProgress p = new PlanProgress(10, 3, 0, 5, true);
        HeartbeatState s = heartbeat.compute(ctx, p);
        assertNotNull(s);
        assertTrue("milestone rule should mention task done, got: " + s.text,
                s.text.toLowerCase().contains("task done"));
    }

    @Test
    public void onPaceRule_firesWhenXpAndProgressBoth() {
        PlanProgress p = new PlanProgress(10, 5, 1500, 5, false);
        HeartbeatState s = heartbeat.compute(ctx, p);
        assertTrue("on-pace should say 'on pace', got: " + s.text,
                s.text.toLowerCase().contains("on pace"));
    }

    @Test
    public void almostThereRule_firesWhenXpButNoProgress() {
        PlanProgress p = new PlanProgress(10, 0, 800, 10, false);
        HeartbeatState s = heartbeat.compute(ctx, p);
        // With no completed tasks the BEHIND_PACE rule fires only when xpDelta == 0,
        // so xpDelta>0 falls through to ALMOST_THERE.
        assertTrue("expected grinding/almost wording, got: " + s.text,
                s.text.toLowerCase().contains("grinding")
                        || s.text.toLowerCase().contains("almost"));
    }

    @Test
    public void idleRule_firesAfterTwoMinutesNoXp() {
        PlanProgress p = new PlanProgress(10, 0, 0, 130, false);
        HeartbeatState s = heartbeat.compute(ctx, p);
        assertTrue("idle should mention break, got: " + s.text,
                s.text.toLowerCase().contains("break"));
    }

    @Test
    public void behindPaceRule_firesWhenPlanLoadedNoProgress() {
        PlanProgress p = new PlanProgress(10, 0, 0, 30, false);
        HeartbeatState s = heartbeat.compute(ctx, p);
        // Not idle (under 120s), not on pace (no xp/progress) → BEHIND_PACE
        assertTrue("behind-pace should mention plan loaded or task #1, got: " + s.text,
                s.text.toLowerCase().contains("plan")
                        || s.text.toLowerCase().contains("task"));
    }

    @Test
    public void defaultRule_firesWhenNothingElseMatches() {
        PlanProgress p = new PlanProgress(0, 0, 0, 5, false);
        HeartbeatState s = heartbeat.compute(ctx, p);
        assertTrue("default should look positive, got: " + s.text,
                s.text.toLowerCase().contains("looking good"));
    }

    @Test
    public void nullInputs_returnSafeDefault() {
        HeartbeatState s = heartbeat.compute(null, null);
        assertNotNull(s);
        assertNotNull(s.text);
        assertTrue("should produce non-empty text", s.text.length() > 0);
    }
}
