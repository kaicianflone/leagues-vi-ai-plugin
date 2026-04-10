package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.VectorIndex;
import com.leaguesai.data.model.Task;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class ChatService {

    static final int MAX_HISTORY = 20;

    private final LlmClient openAiClient;
    private final PlayerContextAssembler contextAssembler;
    private final TaskRepository taskRepo;
    private final VectorIndex vectorIndex;
    private final GoalPlanner goalPlanner;
    private final ItemSourceResolver itemSourceResolver;
    private final PersonaReviewer personaReviewer;

    // Optional callback fired after a planner run succeeds. The plugin sets
    // this to push the plan to the UI (goals panel + overlays). The third
    // arg is the persona review verdict text, which may be null on failure.
    private volatile PlanCallback onPlanCreated;

    /** Functional callback so we can pass three args without nesting BiConsumers. */
    @FunctionalInterface
    public interface PlanCallback {
        void onPlan(String goal, List<PlannedStep> steps, String review);
    }

    // Generation token: every plan-trigger increments this. Callback only
    // fires for the latest generation, so a stale resolver/reviewer that
    // finishes after the user has triggered a newer plan is dropped.
    private final AtomicLong planGeneration = new AtomicLong(0);

    // Thread-safe: all access guarded by synchronized blocks
    private final List<OpenAiClient.Message> conversationHistory =
            Collections.synchronizedList(new ArrayList<>());

    @Inject
    public ChatService(LlmClient openAiClient,
                       PlayerContextAssembler contextAssembler,
                       TaskRepository taskRepo,
                       VectorIndex vectorIndex,
                       GoalPlanner goalPlanner) {
        this.openAiClient = openAiClient;
        this.contextAssembler = contextAssembler;
        this.taskRepo = taskRepo;
        this.vectorIndex = vectorIndex;
        this.goalPlanner = goalPlanner;
        this.itemSourceResolver = openAiClient != null ? new ItemSourceResolver(openAiClient) : null;
        this.personaReviewer = openAiClient != null ? new PersonaReviewer(openAiClient) : null;
    }

    /**
     * Send a user message and return the assistant's reply.
     *
     * Thread-safety: conversationHistory mutations are synchronized. The network
     * call to OpenAI is made OUTSIDE the synchronized block so the lock is not
     * held during a potentially long I/O operation.
     */
    public String sendMessage(String userMessage) throws Exception {
        // Intent detection: if the message looks like a goal, run the planner first
        // so the LLM sees an actual ordered plan in the system prompt context.
        maybeTriggerPlanner(userMessage);

        // RAG: find relevant tasks via semantic search. Done OUTSIDE the lock
        // because both the embedding network call and the vector search may take
        // a non-trivial amount of time. Failures degrade gracefully — chat still
        // works without RAG context.
        List<Task> relevantTasks = Collections.emptyList();
        try {
            if (vectorIndex != null && !vectorIndex.isEmpty() && openAiClient.supportsEmbeddings()) {
                float[] queryEmbedding = openAiClient.getEmbedding(userMessage);
                List<String> taskIds = vectorIndex.searchSimilar(queryEmbedding, 5);
                relevantTasks = taskIds.stream()
                        .map(taskRepo::getById)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("Vector search failed, proceeding without RAG: {}", e.getMessage());
        }

        // Build the system prompt and take a snapshot — all inside the lock
        List<OpenAiClient.Message> snapshot;
        String systemPrompt;

        synchronized (conversationHistory) {
            conversationHistory.add(new OpenAiClient.Message("user", userMessage));

            // Trim oldest messages when history exceeds the cap
            while (conversationHistory.size() > MAX_HISTORY) {
                conversationHistory.remove(0);
            }

            // Assemble player context and build system prompt while still in sync
            // (contextAssembler.assemble() routes through ClientThread — safe to call here)
            PlayerContext ctx = contextAssembler.assemble();
            systemPrompt = PromptBuilder.buildSystemPrompt(ctx, relevantTasks, taskRepo);

            // Snapshot: copy the list so the network call doesn't need the lock
            snapshot = new ArrayList<>(conversationHistory);
        }

        // Network call OUTSIDE the lock — may take seconds
        String response = openAiClient.chatCompletion(systemPrompt, snapshot);

        // Re-acquire lock to record assistant reply
        synchronized (conversationHistory) {
            conversationHistory.add(new OpenAiClient.Message("assistant", response));

            // Trim again in case concurrent calls pushed history over the cap
            while (conversationHistory.size() > MAX_HISTORY) {
                conversationHistory.remove(0);
            }
        }

        return response;
    }

    /**
     * Set a callback fired after a plan is built AND its item sources +
     * persona review have been resolved (or attempted). The callback
     * receives the goal text, the enriched plan, and the review verdict
     * (which may be null if the reviewer call failed).
     */
    public void setOnPlanCreated(PlanCallback callback) {
        this.onPlanCreated = callback;
    }

    /**
     * Atomically invalidates any in-flight plan resolution (increments the
     * generation counter). Called by {@code LeaguesAiPlugin.activateBuild}
     * before firing a build-driven plan, so a stale chat plan cannot overwrite
     * the build plan via the shared {@code onPlanCreated} callback.
     */
    public void cancelPendingPlan() {
        planGeneration.incrementAndGet();
    }

    /**
     * Clear the conversation history.
     */
    public void clearHistory() {
        synchronized (conversationHistory) {
            conversationHistory.clear();
        }
    }

    /**
     * Heuristic: if the user message looks like a goal-setting request, run the
     * planner now and store the resulting plan in the context assembler. This
     * way the system prompt for the upcoming LLM call includes the actual
     * ordered task list, so the LLM can talk about real tasks instead of
     * hallucinating.
     *
     * <p>Triggers on patterns like:
     * <ul>
     *   <li>"complete all karamja easy tasks"</li>
     *   <li>"i want to finish misthalin medium"</li>
     *   <li>"plan kandarin hard tasks"</li>
     *   <li>"/plan karamja easy"</li>
     * </ul>
     *
     * <p>If no intent is detected, this is a no-op and chat proceeds normally.
     */
    private void maybeTriggerPlanner(String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) return;
        if (taskRepo == null || goalPlanner == null) return;

        String lower = userMessage.toLowerCase().trim();

        // Trigger phrases — covers explicit slash commands, "plan ..." prefix,
        // and natural-language commit phrasings a player might use after the AI
        // has proposed a plan in conversation. The planner is fuzzy enough that
        // false positives bail silently (resolveGoalTasks returns empty).
        boolean triggered = false;

        // Explicit prefixes
        if (lower.startsWith("/plan")) triggered = true;
        else if (lower.startsWith("plan ")) triggered = true;
        else if (lower.startsWith("set goal")) triggered = true;
        else if (lower.startsWith("set my goal")) triggered = true;
        else if (lower.startsWith("start plan")) triggered = true;
        else if (lower.startsWith("make plan")) triggered = true;
        else if (lower.startsWith("build plan")) triggered = true;
        else if (lower.startsWith("load plan")) triggered = true;
        else if (lower.startsWith("lock in")) triggered = true;
        else if (lower.startsWith("lock it in")) triggered = true;

        // Natural-language phrases anywhere in the message
        if (!triggered) {
            String[] phrases = {
                "make me a plan", "build me a plan", "build a plan", "make a plan",
                "give me a plan", "plan out", "plan it out", "start planning",
                "let's plan", "lets plan", "create a plan", "draw up a plan",
                "i want to complete all", "i wanna complete all",
                "i want to do all", "i wanna do all",
                "i want to finish all", "i wanna finish all",
                "complete all the", "finish all the", "do all the",
                "complete every", "finish every", "do every",
                "knock out all", "knock out the",
                "yes plan", "yes please plan", "ok plan", "ok let's plan",
                "go ahead and plan", "load the plan", "load that plan"
            };
            for (String phrase : phrases) {
                if (lower.contains(phrase)) {
                    triggered = true;
                    break;
                }
            }
        }

        if (!triggered) {
            return;
        }
        log.info("Planner triggered by message: '{}'", userMessage);

        // Bump generation: any in-flight resolver/reviewer for an older plan
        // will be ignored when it tries to fire the callback.
        final long myGen = planGeneration.incrementAndGet();

        try {
            // Composite goal path: "plan unlock the Grimoire relic" etc. Parsed
            // against the repo so the resolver works off real unlock costs
            // and the player's current league-point balance, not keyword
            // matching. Returns TASK_BATCH for phrases the parser doesn't
            // recognise so we fall through to the existing flat path below.
            PlayerContext ctxForParser = contextAssembler.assemble();
            GoalSpec spec = GoalSpecParser.parse(userMessage, taskRepo);
            List<Task> targets;
            CompositeGoal composite = null;

            if (spec.getType() == GoalType.RELIC
                    || spec.getType() == GoalType.AREA
                    || spec.getType() == GoalType.PACT) {
                composite = goalPlanner.resolveCompositeGoal(spec, ctxForParser);
                log.info("Composite goal resolved: type={} target={} reachable={} gap={} covered={} children={}",
                        spec.getType(), spec.getTargetName(), composite.isReachable(),
                        composite.getPointsGap(), composite.getCoveredBy(),
                        composite.getChildren().size());
                targets = new ArrayList<>(composite.getTaskBatch());
            } else {
                targets = goalPlanner.resolveGoalTasks(userMessage);
            }

            if (targets.isEmpty() && composite == null) {
                log.info("Planner: no tasks matched goal '{}'", userMessage);
                return;
            }

            List<Task> sorted;
            if (composite != null) {
                // Task batch from the composite resolver is already curated
                // and ordered by points-per-effort. Skip DAG expansion — we
                // don't want prereq chains pulling in unrelated tasks for a
                // relic unlock goal.
                sorted = targets;
            } else {
                Set<String> completed = new HashSet<>(); // TODO: pull from contextAssembler when wired
                List<Task> dag = goalPlanner.buildDag(targets, completed);
                try {
                    sorted = goalPlanner.topologicalSort(dag);
                } catch (IllegalStateException cycle) {
                    log.warn("Planner: cycle detected, falling back to insertion order: {}", cycle.getMessage());
                    sorted = dag;
                }
            }

            // Reuse the context we already assembled at the top instead of
            // routing through ClientThread again for the player's location.
            WorldPoint loc = ctxForParser.getLocation();
            List<Task> optimized = PlannerOptimizer.optimizeOrder(sorted, loc);

            // Convert tasks → PlannedSteps with OverlayData populated from whatever
            // the scraper captured. Many tasks won't have a resolved location yet,
            // so the overlay will only show for tasks with a known WorldPoint.
            List<PlannedStep> steps = optimized.stream()
                    .map(t -> {
                        List<Integer> npcIds = new ArrayList<>();
                        if (t.getTargetNpcs() != null) {
                            t.getTargetNpcs().forEach(n -> npcIds.add(n.getId()));
                        }
                        List<Integer> objIds = new ArrayList<>();
                        if (t.getTargetObjects() != null) {
                            t.getTargetObjects().forEach(o -> objIds.add(o.getId()));
                        }
                        List<Integer> itemIds = new ArrayList<>();
                        if (t.getTargetItems() != null) {
                            t.getTargetItems().forEach(i -> itemIds.add(i.getId()));
                        }
                        com.leaguesai.overlay.OverlayData overlayData =
                                com.leaguesai.overlay.OverlayData.builder()
                                        .targetTile(t.getLocation())
                                        .targetNpcIds(npcIds)
                                        .targetObjectIds(objIds)
                                        .targetItemIds(itemIds)
                                        .pathPoints(Collections.emptyList())
                                        .widgetIds(Collections.emptyList())
                                        .showArrow(t.getLocation() != null)
                                        .showMinimap(t.getLocation() != null)
                                        .showWorldMap(t.getLocation() != null)
                                        .build();
                        return PlannedStep.builder()
                                .task(t)
                                .destination(t.getLocation())
                                .instruction(t.getName())
                                .overlayData(overlayData)
                                .build();
                    })
                    .collect(Collectors.toList());

            contextAssembler.setCurrentGoal(userMessage);
            contextAssembler.setCurrentPlan(steps);
            log.info("Planner: built {} planned steps for goal '{}'", steps.size(), userMessage);

            // Enrich the plan with item sources + run persona review. Two LLM
            // calls. Skipped entirely when the step list is empty (composite
            // PACT goals, or composite relic/area goals where the player can
            // already afford the unlock) — running a persona review on an
            // empty plan burns tokens and produces junk verdict text.
            List<PlannedStep> enriched = steps;
            String review = null;
            if (!steps.isEmpty()) {
                if (itemSourceResolver != null) {
                    try {
                        enriched = itemSourceResolver.resolveBatch(steps);
                    } catch (Exception resolverErr) {
                        log.warn("Item source resolution failed: {}", resolverErr.getMessage());
                    }
                }

                if (personaReviewer != null) {
                    try {
                        review = personaReviewer.review(userMessage, enriched);
                    } catch (Exception revErr) {
                        log.warn("Persona review failed: {}", revErr.getMessage());
                    }
                }
            } else {
                log.info("Planner: empty step list (composite PACT or already-affordable goal), "
                        + "skipping item source + persona review");
            }

            // Stale-plan guard: if the user fired another plan while we were
            // resolving items / running review, drop this one.
            if (myGen != planGeneration.get()) {
                log.info("Planner: plan generation {} superseded by {}, dropping callback",
                        myGen, planGeneration.get());
                return;
            }

            // Push enriched plan to context so the next chat turn sees the
            // sourced items in its system prompt.
            contextAssembler.setCurrentPlan(enriched);

            // Notify UI / overlay controller
            PlanCallback cb = onPlanCreated;
            if (cb != null) {
                try {
                    cb.onPlan(userMessage, enriched, review);
                } catch (Exception cbErr) {
                    log.warn("onPlanCreated callback threw: {}", cbErr.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Planner failed for goal '{}': {}", userMessage, e.getMessage());
        }
    }
}
