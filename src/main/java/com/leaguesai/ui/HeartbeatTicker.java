package com.leaguesai.ui;

import com.leaguesai.agent.CoachPulseService;
import com.leaguesai.agent.HeartbeatProvider;
import com.leaguesai.agent.HeartbeatProvider.HeartbeatState;
import com.leaguesai.agent.HeartbeatProvider.PlanProgress;
import com.leaguesai.agent.PlayerContext;
import com.leaguesai.agent.PlayerContextAssembler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives the once-per-minute heartbeat label in both ChatPanel and
 * GoalsPanel. Every 5th tick, defers to {@link CoachPulseService} for a
 * richer LLM-backed pulse instead of the local rules.
 *
 * <p>Threading:
 * <ul>
 *   <li>The {@link javax.swing.Timer} fires on the EDT.</li>
 *   <li>The EDT immediately hands the entire tick body to {@code llmExecutor}.
 *       This is required because {@code PlayerContextAssembler.assemble()}
 *       blocks the calling thread for up to 5 seconds waiting on the
 *       RuneLite ClientThread, which would freeze the UI if done on the EDT.</li>
 *   <li>The local-rule render and the LLM pulse render both post back to
 *       the EDT via {@code SwingUtilities.invokeLater}.</li>
 *   <li>{@code llmExecutor} is single-threaded, so {@code lastXp},
 *       {@code lastCompletedCount}, and other mutable tick state are
 *       only touched from one thread (no synchronization required).</li>
 * </ul>
 */
@Slf4j
public class HeartbeatTicker {

    /** 60s tick. */
    static final int TICK_MS = 60_000;
    /** Every Nth tick uses the LLM pulse instead of local rules. 5 → every 5 minutes. */
    static final int LLM_PULSE_EVERY_N_TICKS = 5;

    private final ChatPanel chatPanel;
    private final GoalsPanel goalsPanel;
    private final HeartbeatProvider localProvider;
    private final CoachPulseService coachPulseService;
    private final PlayerContextAssembler contextAssembler;
    private final ExecutorService llmExecutor;

    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
    private int lastCompletedCount = 0;
    private long lastActionAtMs = System.currentTimeMillis();
    private int lastTotalTasks = 0;

    /**
     * Flipped to false in {@link #stop()}. Background tick runnables that
     * complete after stop bail before rendering, so a slow LLM pulse from a
     * torn-down ticker can never overwrite a fresh ticker's label.
     */
    private volatile boolean running = false;

    private Timer timer;

    public HeartbeatTicker(ChatPanel chatPanel,
                           GoalsPanel goalsPanel,
                           HeartbeatProvider localProvider,
                           CoachPulseService coachPulseService,
                           PlayerContextAssembler contextAssembler,
                           ExecutorService llmExecutor) {
        this.chatPanel = chatPanel;
        this.goalsPanel = goalsPanel;
        this.localProvider = localProvider;
        this.coachPulseService = coachPulseService;
        this.contextAssembler = contextAssembler;
        this.llmExecutor = llmExecutor;
    }

    /** Start ticking. Idempotent. */
    public void start() {
        if (timer != null) return;
        running = true;
        timer = new Timer(TICK_MS, e -> tick());
        timer.setInitialDelay(TICK_MS);
        timer.setCoalesce(true);
        timer.start();
        log.info("HeartbeatTicker started (every {} ms, LLM pulse every {} ticks)",
                TICK_MS, LLM_PULSE_EVERY_N_TICKS);
    }

    /** Stop ticking. Safe to call from shutdown. */
    public void stop() {
        running = false;
        if (timer != null) {
            timer.stop();
            timer = null;
            log.info("HeartbeatTicker stopped");
        }
    }

    /**
     * Called by the Swing Timer on the EDT. Hands the real work to
     * {@code llmExecutor} so that {@code contextAssembler.assemble()}
     * (which blocks waiting on ClientThread) does not freeze the UI.
     *
     * <p>Package-private for tests.
     */
    void tick() {
        if (llmExecutor == null || llmExecutor.isShutdown()) {
            return;
        }
        try {
            llmExecutor.submit(this::runTickOffEdt);
        } catch (RejectedExecutionException ree) {
            // Executor was shut down between the check above and submit.
            // Safe to ignore — we're tearing down.
            log.debug("HeartbeatTicker: tick rejected (executor shutting down)");
        }
    }

    /**
     * Runs on {@code llmExecutor} (single-thread). Assembles player context,
     * computes the heartbeat state, and posts the rendered text back to the
     * EDT via {@link #render(String)}.
     */
    private void runTickOffEdt() {
        if (!running) return;
        int n = tickCounter.incrementAndGet();
        boolean isLlmTick = (n % LLM_PULSE_EVERY_N_TICKS) == 0;

        PlayerContext ctx;
        try {
            ctx = contextAssembler.assemble();
        } catch (Exception e) {
            log.warn("HeartbeatTicker: failed to assemble player context: {}", e.getMessage());
            return;
        }

        PlanProgress progress = computeProgress(ctx);

        String text;
        if (isLlmTick && coachPulseService != null) {
            String pulse = coachPulseService.pulse();
            text = (pulse == null || pulse.isEmpty())
                    ? localProvider.compute(ctx, progress).text
                    : pulse;
        } else {
            text = localProvider.compute(ctx, progress).text;
        }

        // Re-check running AFTER the (potentially slow) LLM pulse so a torn-down
        // ticker doesn't overwrite the fresh ticker's label with a stale message.
        if (!running) return;
        render(text);
    }

    private PlanProgress computeProgress(PlayerContext ctx) {
        // XP delta — sum across all skills since last tick
        int xpDelta = 0;
        if (ctx.getXp() != null) {
            for (Map.Entry<Skill, Integer> e : ctx.getXp().entrySet()) {
                Integer prev = lastXp.get(e.getKey());
                int now = e.getValue() != null ? e.getValue() : 0;
                if (prev != null) xpDelta += Math.max(0, now - prev);
                lastXp.put(e.getKey(), now);
            }
        }

        int completedNow = ctx.getCompletedTasks() != null ? ctx.getCompletedTasks().size() : 0;
        boolean justCompleted = completedNow > lastCompletedCount;
        lastCompletedCount = completedNow;

        // totalTasks tracks the active plan size
        int total = ctx.getCurrentPlan() != null ? ctx.getCurrentPlan().size() : 0;
        if (total != lastTotalTasks) lastTotalTasks = total;

        long now = System.currentTimeMillis();
        if (xpDelta > 0 || justCompleted) {
            lastActionAtMs = now;
        }
        long secondsSinceAction = (now - lastActionAtMs) / 1000;

        return new PlanProgress(total, completedNow, xpDelta, secondsSinceAction, justCompleted);
    }

    private void render(String text) {
        if (chatPanel != null) chatPanel.setHeartbeatText(text);
        if (goalsPanel != null) goalsPanel.setHeartbeatText(text);
    }
}
