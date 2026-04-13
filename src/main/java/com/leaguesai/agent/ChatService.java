package com.leaguesai.agent;

import com.leaguesai.LeaguesAiConfig;
import com.leaguesai.data.ChatHistoryStore;
import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.UserPreferences;
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
    private volatile ItemSourceResolver itemSourceResolver;
    private final PersonaReviewer personaReviewer;
    private volatile ItemDependencyGraph itemDependencyGraph;
    private volatile LeaguesAiConfig leaguesAiConfig;

    // Optional callback fired after a planner run succeeds. The plugin sets
    // this to push the plan to the UI (goals panel + overlays). The third
    // arg is the persona review verdict text, which may be null on failure.
    private volatile PlanCallback onPlanCreated;

    // Optional post-PlannedStep proximity optimizer (relic-aware nearest-neighbour).
    // Set via setter so the existing 5-arg constructor and tests remain unchanged.
    private volatile ProximityOptimizer proximityOptimizer;

    // Optional history store for session persistence. Null = no persistence
    // (tests and pre-load state). Set by LeaguesAiPlugin after DB loads.
    private volatile ChatHistoryStore historyStore;

    // Optional user preferences (model + persona selection). Null = all personas.
    private volatile UserPreferences userPreferences;

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

    /** Backward-compat constructor for tests that don't inject LeaguesAiConfig. Defaults to leagues mode = true. */
    public ChatService(LlmClient openAiClient,
                       PlayerContextAssembler contextAssembler,
                       TaskRepository taskRepo,
                       VectorIndex vectorIndex,
                       GoalPlanner goalPlanner) {
        this(openAiClient, contextAssembler, taskRepo, vectorIndex, goalPlanner, null);
    }

    @Inject
    public ChatService(LlmClient openAiClient,
                       PlayerContextAssembler contextAssembler,
                       TaskRepository taskRepo,
                       VectorIndex vectorIndex,
                       GoalPlanner goalPlanner,
                       LeaguesAiConfig leaguesAiConfig) {
        this.openAiClient = openAiClient;
        this.contextAssembler = contextAssembler;
        this.taskRepo = taskRepo;
        this.vectorIndex = vectorIndex;
        this.goalPlanner = goalPlanner;
        this.leaguesAiConfig = leaguesAiConfig;
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
        final long genBefore = planGeneration.get();
        maybeTriggerPlanner(userMessage);
        // True when maybeTriggerPlanner built a new plan for this specific message.
        final boolean planJustCreated = planGeneration.get() != genBefore;

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
            java.util.List<String> personas = userPreferences != null
                    ? userPreferences.getSelectedPersonas() : null;
            systemPrompt = PromptBuilder.buildSystemPrompt(ctx, relevantTasks, taskRepo, null, personas, planJustCreated);

            // Snapshot: copy the list so the network call doesn't need the lock
            snapshot = new ArrayList<>(conversationHistory);
        }

        // Network call OUTSIDE the lock — may take seconds
        String response = openAiClient.chatCompletion(systemPrompt, snapshot);

        // Re-acquire lock to record assistant reply, then snapshot for persistence
        List<ChatHistoryStore.Entry> toSave = null;
        synchronized (conversationHistory) {
            conversationHistory.add(new OpenAiClient.Message("assistant", response));

            // Trim again in case concurrent calls pushed history over the cap
            while (conversationHistory.size() > MAX_HISTORY) {
                conversationHistory.remove(0);
            }

            if (historyStore != null) {
                toSave = new ArrayList<>(conversationHistory.size());
                for (OpenAiClient.Message m : conversationHistory) {
                    toSave.add(new ChatHistoryStore.Entry(m.getRole(), m.getContent()));
                }
            }
        }
        // Persist OUTSIDE the lock — I/O must not hold the conversation lock.
        if (toSave != null) {
            historyStore.save(toSave);
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

    /** Plugs in the relic-aware proximity optimizer (null = skip reordering). */
    public void setProximityOptimizer(ProximityOptimizer optimizer) {
        this.proximityOptimizer = optimizer;
    }

    /** Plugs in the history store for session persistence across restarts. */
    public void setHistoryStore(ChatHistoryStore store) {
        this.historyStore = store;
    }

    /** Plugs in user preferences (model + persona selection). */
    public void setUserPreferences(UserPreferences prefs) {
        this.userPreferences = prefs;
        if (personaReviewer != null) personaReviewer.setUserPreferences(prefs);
    }

    /** Plugs in the item dependency graph for item goal detection in GoalSpecParser,
     *  and rebuilds the {@link ItemSourceResolver} to enable DB-first lookups. */
    public void setItemDependencyGraph(ItemDependencyGraph graph) {
        this.itemDependencyGraph = graph;
        if (openAiClient != null) {
            this.itemSourceResolver = new ItemSourceResolver(openAiClient, graph);
        }
    }

    /**
     * Pre-populate conversation history from a prior session (called on startup
     * after the store is loaded). Does NOT persist back — the store already has
     * this data.
     */
    public void loadHistory(List<ChatHistoryStore.Entry> entries) {
        if (entries == null || entries.isEmpty()) return;
        synchronized (conversationHistory) {
            conversationHistory.clear();
            for (ChatHistoryStore.Entry e : entries) {
                if (e.role != null && e.content != null) {
                    conversationHistory.add(new OpenAiClient.Message(e.role, e.content));
                }
            }
        }
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

    /** Package-private: returns the current plan generation counter. Tests use this to
     *  verify that {@link #cancelPendingPlan()} actually increments the counter. */
    long getPlanGeneration() {
        return planGeneration.get();
    }

    /**
     * Clear the conversation history (in-memory and persisted store).
     */
    public void clearHistory() {
        synchronized (conversationHistory) {
            conversationHistory.clear();
        }
        ChatHistoryStore store = historyStore;
        if (store != null) store.clear();
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

        // Item/craft-intent: "get X", "make X", "i need X", etc. Only trigger when
        // the itemDependencyGraph is loaded AND an actual known item name appears in
        // the phrase, so "I need to level up" doesn't trigger the planner.
        if (!triggered && itemDependencyGraph != null && !itemDependencyGraph.isEmpty()) {
            if (lower.contains("get ") || lower.contains("need ") || lower.contains("want ")
                    || lower.contains("make ") || lower.contains("farm ")
                    || lower.contains("craft ") || lower.contains("smith ")
                    || lower.contains("fletch ") || lower.contains("cook ")
                    || lower.contains("brew ") || lower.contains("obtain ")
                    || lower.contains("i need") || lower.contains("i want")
                    || lower.startsWith("get ") || lower.startsWith("need ")
                    || lower.startsWith("make ")) {
                if (itemDependencyGraph.findLongestMatchingItem(lower) != null) {
                    triggered = true;
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
            boolean leaguesMode = leaguesAiConfig != null && leaguesAiConfig.leaguesMode();
            GoalSpec spec = GoalSpecParser.parse(userMessage, taskRepo, itemDependencyGraph, leaguesMode);
            List<Task> targets = new ArrayList<>();
            CompositeGoal composite = null;

            if (spec.getType() == GoalType.RELIC
                    || spec.getType() == GoalType.AREA
                    || spec.getType() == GoalType.PACT
                    || spec.getType() == GoalType.ITEM
                    || spec.getType() == GoalType.CRAFT) {
                composite = goalPlanner.resolveCompositeGoal(spec, ctxForParser);
                if (composite == null) {
                    log.info("Planner: leagues goal '{}' skipped (ironman mode)", spec.getType());
                    return;
                }
                log.info("Composite goal resolved: type={} target={} reachable={} gap={} covered={} children={}",
                        spec.getType(), spec.getTargetName(), composite.isReachable(),
                        composite.getPointsGap(), composite.getCoveredBy(),
                        composite.getChildren().size());
                targets = new ArrayList<>(composite.getTaskBatch());
            } else {
                // When GoalSpecParser returned TASK_BATCH but the message was triggered,
                // try one more item scan in case the phrase didn't have a recognized keyword
                // (e.g., "make me a plan to buy a staff of air" → no "need"/"get" keyword).
                if (itemDependencyGraph != null && !itemDependencyGraph.isEmpty()) {
                    String lowerMsg = userMessage.toLowerCase();
                    com.leaguesai.data.model.ItemDependency found =
                            itemDependencyGraph.findLongestMatchingItem(lowerMsg);
                    if (found != null) {
                        String lowerMsg2 = userMessage.toLowerCase();
                        boolean isCraftMsg = lowerMsg2.contains("make ") || lowerMsg2.contains("craft ")
                                || lowerMsg2.contains("smith ") || lowerMsg2.contains("fletch ")
                                || lowerMsg2.contains("brew ") || lowerMsg2.contains("cook ");
                        GoalSpec itemSpec = GoalSpec.builder()
                                .type(isCraftMsg ? GoalType.CRAFT : GoalType.ITEM)
                                .targetId(found.getItemId())
                                .targetName(found.getItemName())
                                .rawPhrase(userMessage)
                                .unlockCost(0)
                                .build();
                        composite = goalPlanner.resolveCompositeGoal(itemSpec, ctxForParser);
                        if (composite != null) {
                            log.info("Planner: fallback item detection found '{}' in '{}'",
                                    found.getItemName(), userMessage);
                            targets = new ArrayList<>(composite.getTaskBatch());
                        }
                    }
                }
                if (composite == null) {
                    targets = goalPlanner.resolveGoalTasks(userMessage);
                }
            }

            // If taskBatch is empty but the wiki lookup produced directSteps, use those.
            // Prepend any skilling steps (train skill before crafting/smithing the item).
            List<PlannedStep> steps;
            if (composite != null && targets.isEmpty()
                    && composite.getDirectSteps() != null && !composite.getDirectSteps().isEmpty()) {
                List<PlannedStep> combined = new ArrayList<>();
                if (composite.getSkillingSteps() != null) combined.addAll(composite.getSkillingSteps());
                combined.addAll(composite.getDirectSteps());
                steps = combined;
                log.info("Planner: using {} direct steps (+ {} skilling) from wiki lookup for '{}'",
                        composite.getDirectSteps().size(),
                        composite.getSkillingSteps() != null ? composite.getSkillingSteps().size() : 0,
                        userMessage);
            } else {
                if (targets.isEmpty() && composite == null) {
                    log.info("Planner: no tasks matched goal '{}'", userMessage);
                    return;
                }

                List<Task> sorted;
                if (composite != null) {
                    // Task batch from the composite resolver is already curated
                    // and ordered by points-per-effort. Skip DAG expansion.
                    sorted = targets;
                } else {
                    Set<String> completed = new HashSet<>();
                    List<Task> dag = goalPlanner.buildDag(targets, completed);
                    try {
                        sorted = goalPlanner.topologicalSort(dag);
                    } catch (IllegalStateException cycle) {
                        log.warn("Planner: cycle detected, falling back to insertion order: {}", cycle.getMessage());
                        sorted = dag;
                    }
                }

                WorldPoint loc = ctxForParser.getLocation();
                List<Task> optimized = PlannerOptimizer.optimizeOrder(sorted, loc);
                List<PlannedStep> taskSteps = buildSteps(optimized);
                // Prepend skill-training steps when the item has a craft/smith dependency chain.
                if (composite != null && composite.getSkillingSteps() != null
                        && !composite.getSkillingSteps().isEmpty()) {
                    List<PlannedStep> combined = new ArrayList<>(composite.getSkillingSteps());
                    combined.addAll(taskSteps);
                    steps = combined;
                } else {
                    steps = taskSteps;
                }
            }

            // Relic-aware proximity reorder (post-PlannedStep pass; no-op when null).
            if (proximityOptimizer != null && !steps.isEmpty()) {
                Set<String> unlockedAreas = ctxForParser.getUnlockedAreas();
                steps = proximityOptimizer.optimize(steps, ctxForParser, unlockedAreas);
            }

            contextAssembler.setCurrentGoal(userMessage);
            contextAssembler.setCurrentPlan(steps);
            log.info("Planner: built {} planned steps for goal '{}'", steps.size(), userMessage);

            // Enrich the plan with item sources + run persona review with rebuild loop.
            // Skipped when the step list is empty (composite PACT goals etc.).
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
                    // Rebuild loop: if personas say "rebuild", reorder the plan using their
                    // critique and re-review. Cap at MAX_REVIEW_RETRIES to avoid runaway cost.
                    final int MAX_REVIEW_RETRIES = 3;
                    String priorCritique = null;
                    for (int attempt = 0; attempt < MAX_REVIEW_RETRIES; attempt++) {
                        try {
                            review = personaReviewer.review(userMessage, enriched, priorCritique);
                        } catch (Exception revErr) {
                            log.warn("Persona review attempt {} failed: {}", attempt + 1, revErr.getMessage());
                            break;
                        }
                        String verdict = PersonaReviewer.extractVerdict(review);
                        log.info("Persona review attempt {}/{}: verdict={}", attempt + 1, MAX_REVIEW_RETRIES, verdict);
                        if (!"rebuild".equals(verdict)) break;
                        if (attempt == MAX_REVIEW_RETRIES - 1) {
                            log.info("Persona review: still rebuild after {} attempts, keeping final review", MAX_REVIEW_RETRIES);
                            break;
                        }
                        // Try to reorder the plan based on persona critique
                        try {
                            String refinementPrompt = PromptBuilder.buildPlanRefinementPrompt(
                                    userMessage, enriched, review);
                            String refinementReply = openAiClient.chatCompletion(
                                    "You are a Leagues VI plan optimizer. Respond with only a JSON array of task IDs.",
                                    java.util.Collections.singletonList(
                                            new OpenAiClient.Message("user", refinementPrompt)));
                            List<PlannedStep> reordered = reorderByLlmSuggestion(enriched, refinementReply);
                            if (!reordered.isEmpty()) {
                                priorCritique = review;
                                enriched = reordered;
                                log.info("Planner: rebuilt plan ({} steps) after persona critique", enriched.size());
                            } else {
                                log.warn("Persona rebuild: LLM reorder produced empty list, keeping current order");
                                break;
                            }
                        } catch (Exception refineErr) {
                            log.warn("Plan refinement call failed: {}", refineErr.getMessage());
                            break;
                        }
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

    /**
     * Convert an ordered list of {@link Task}s into {@link PlannedStep}s with
     * {@link com.leaguesai.overlay.OverlayData} populated from scraped task data.
     * Steps with a null location still appear in the plan but have overlays
     * disabled ({@code showArrow/showMinimap/showWorldMap = false}).
     *
     * <p>Static so {@code LeaguesAiPlugin.activateBuild} can share the same
     * conversion logic without duplicating the stream.
     */
    public static List<PlannedStep> buildSteps(List<Task> tasks) {
        return tasks.stream()
                .map(t -> {
                    List<Integer> npcIds = new ArrayList<>();
                    if (t.getTargetNpcs() != null) t.getTargetNpcs().forEach(n -> npcIds.add(n.getId()));
                    List<Integer> objIds = new ArrayList<>();
                    if (t.getTargetObjects() != null) t.getTargetObjects().forEach(o -> objIds.add(o.getId()));
                    List<Integer> itemIds = new ArrayList<>();
                    if (t.getTargetItems() != null) t.getTargetItems().forEach(i -> itemIds.add(i.getId()));
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
    }

    /**
     * Parses the LLM's JSON array of task IDs and reorders {@code steps} to match.
     * Tasks not mentioned by the LLM are appended at the end. Returns an empty
     * list if parsing fails entirely.
     */
    private static List<PlannedStep> reorderByLlmSuggestion(List<PlannedStep> steps, String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        // Extract the JSON array portion (LLM sometimes adds surrounding text)
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) return Collections.emptyList();
        String arrayStr = json.substring(start + 1, end);
        // Parse quoted IDs
        java.util.List<String> orderedIds = new java.util.ArrayList<>();
        for (String token : arrayStr.split(",")) {
            String id = token.replaceAll("[\"'\\s]", "");
            if (!id.isEmpty()) orderedIds.add(id);
        }
        if (orderedIds.isEmpty()) return Collections.emptyList();

        // Build id → step map
        java.util.Map<String, PlannedStep> byId = new java.util.LinkedHashMap<>();
        for (PlannedStep s : steps) {
            if (s.getTask() != null && s.getTask().getId() != null) {
                byId.put(s.getTask().getId(), s);
            }
        }
        List<PlannedStep> reordered = new java.util.ArrayList<>();
        for (String id : orderedIds) {
            PlannedStep s = byId.remove(id);
            if (s != null) reordered.add(s);
        }
        // Append any steps not mentioned by the LLM
        reordered.addAll(byId.values());
        return reordered;
    }
}
