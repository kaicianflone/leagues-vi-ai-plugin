package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import com.leaguesai.overlay.OverlayData;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

@Slf4j
@Singleton
public class GoalPlanner {

    /**
     * How many tasks to suggest when a relic/area goal has an unknown unlock
     * cost (i.e. {@code spec.unlockCost == 0}). Pre-launch (2026-04-08..15)
     * the OSRS Wiki hasn't published Leagues VI unlock costs yet, so every
     * relic/area goal arrives with cost zero. Rather than short-circuiting to
     * an empty plan, the resolver picks this many highest-value achievable
     * tasks as a useful pre-launch suggestion. On launch day the cost-driven
     * path takes over automatically once the scraper captures real numbers.
     */
    private static final int UNKNOWN_COST_TASK_CAP = 10;

    private final TaskRepository taskRepo;
    private final ItemDependencyGraph itemDependencyGraph;

    /** On-demand wiki lookup for catalog items with no dependency chain. Set post-construction. */
    private volatile WikiItemLookup wikiItemLookup;

    @Inject
    public GoalPlanner(TaskRepository taskRepo, ItemDependencyGraph itemDependencyGraph) {
        this.taskRepo = taskRepo;
        this.itemDependencyGraph = itemDependencyGraph;
    }

    /** Plugs in the wiki item lookup. Called from LeaguesAiPlugin after construction. */
    public void setWikiItemLookup(WikiItemLookup lookup) {
        this.wikiItemLookup = lookup;
    }

    /**
     * BFS walk: for each target, recursively collect all prerequisites via
     * taskRepo.getPrerequisites(). Prune tasks in completedTaskIds. Deduplicate.
     * Returns tasks in discovery order.
     */
    public List<Task> buildDag(List<Task> targetTasks, Set<String> completedTaskIds) {
        if (targetTasks == null) targetTasks = Collections.emptyList();
        if (completedTaskIds == null) completedTaskIds = Collections.emptySet();

        LinkedHashMap<String, Task> discovered = new LinkedHashMap<>();
        Deque<Task> queue = new ArrayDeque<>(targetTasks);

        while (!queue.isEmpty()) {
            Task current = queue.poll();
            if (current == null) continue;
            String id = current.getId();

            // Skip if already discovered or already completed
            if (discovered.containsKey(id) || completedTaskIds.contains(id)) {
                continue;
            }

            discovered.put(id, current);

            // Enqueue prerequisites
            List<Task> prereqs = taskRepo.getPrerequisites(id);
            if (prereqs != null) {
                for (Task prereq : prereqs) {
                    if (prereq != null && !discovered.containsKey(prereq.getId())
                            && !completedTaskIds.contains(prereq.getId())) {
                        queue.add(prereq);
                    }
                }
            }
        }

        return new ArrayList<>(discovered.values());
    }

    /**
     * Kahn's algorithm topological sort with cycle detection.
     * Throws IllegalStateException if a cycle is detected.
     * Self-dependencies are silently ignored.
     */
    public List<Task> topologicalSort(List<Task> tasks) {
        Map<String, Task> taskMap = new LinkedHashMap<>();
        for (Task t : tasks) taskMap.put(t.getId(), t);

        Map<String, Set<String>> inEdges = new HashMap<>();
        for (Task t : tasks) inEdges.put(t.getId(), new HashSet<>());
        for (Task t : tasks) {
            if (t.getTasksRequired() != null) {
                for (String prereqId : t.getTasksRequired()) {
                    if (taskMap.containsKey(prereqId) && !prereqId.equals(t.getId())) {
                        inEdges.get(t.getId()).add(prereqId);
                    }
                }
            }
        }

        List<Task> sorted = new ArrayList<>();
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Set<String>> e : inEdges.entrySet()) {
            if (e.getValue().isEmpty()) ready.add(e.getKey());
        }

        while (!ready.isEmpty()) {
            String id = ready.poll();
            sorted.add(taskMap.get(id));
            for (Map.Entry<String, Set<String>> e : inEdges.entrySet()) {
                if (e.getValue().remove(id) && e.getValue().isEmpty()) {
                    ready.add(e.getKey());
                }
            }
        }

        // Cycle detection
        if (sorted.size() < tasks.size()) {
            Set<String> remaining = new HashSet<>(taskMap.keySet());
            for (Task t : sorted) remaining.remove(t.getId());
            throw new IllegalStateException("Cycle detected in task dependencies: " + remaining);
        }

        return sorted;
    }

    /**
     * Resolve a parsed {@link GoalSpec} into a full {@link CompositeGoal}:
     * compute the point gap between the player's current league points and
     * the target's unlock cost, pick a batch of achievable tasks that closes
     * the gap, and emit child goals for any missing prerequisites.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>{@link GoalType#PACT} → no cost, return an empty batch (reachable).</li>
     *   <li>{@link GoalType#RELIC} / {@link GoalType#AREA} → compute
     *       {@code gap = unlockCost - currentLeaguePoints}. If {@code <= 0}
     *       the target is already affordable, return empty batch.</li>
     *   <li>Filter {@code taskRepo.getAllTasks()} to achievable tasks (not
     *       completed, skill levels met, area already unlocked).</li>
     *   <li>Sort descending by {@code points / effort} where effort is the
     *       difficulty tier (easy=1 .. master=5).</li>
     *   <li>Greedy-select until the cumulative point total covers the gap.</li>
     *   <li>If nothing achievable closes the gap, emit a child
     *       {@link GoalType#AREA} goal for the area whose tasks would
     *       contribute the most points once unlocked.</li>
     * </ol>
     *
     * <p>Does NOT call an LLM. Uses only local data from the repo + player
     * context. The LLM sees the resulting composite via the prompt context
     * and can still re-rank / reject.
     */
    public CompositeGoal resolveCompositeGoal(GoalSpec spec, PlayerContext ctx) {
        Objects.requireNonNull(spec, "GoalSpec must not be null");

        // Gate leagues-only goal types in ironman mode (defense-in-depth — GoalSpecParser
        // already short-circuits these, but callers using the 3-arg backward-compat overload
        // bypass that gating). Guard ctx != null for tests that pass null ctx (BUILD goal tests).
        if (ctx != null && !ctx.isLeaguesMode() && (spec.getType() == GoalType.RELIC
                || spec.getType() == GoalType.AREA
                || spec.getType() == GoalType.PACT)) {
            return null;
        }

        // ITEM goals: dependency graph expansion, no point gap.
        if (spec.getType() == GoalType.ITEM) {
            return resolveItemGoal(spec, ctx);
        }

        // CRAFT goals: wiki recipe lookup → build ingredient + craft steps.
        if (spec.getType() == GoalType.CRAFT) {
            return resolveCraftGoal(spec, ctx);
        }

        // BUILD goals: multi-terminal DAG, no gap-closing.
        // Each terminal task ID is a gear-reward task. Build the prereq DAG
        // backward from all terminals, topo-sort, return the ordered chain.
        if (spec.getType() == GoalType.BUILD) {
            Set<String> terminalIds = spec.getTerminalTaskIds();
            if (terminalIds == null || terminalIds.isEmpty()) {
                // Goals-only build: no gear tasks found — return empty batch
                // so BuildExpander can detect goals-only mode.
                return CompositeGoal.builder()
                        .root(spec)
                        .pointsGap(0)
                        .coveredBy(0)
                        .reachable(true)
                        .build();
            }

            // Resolve terminal task IDs to Task objects
            List<Task> terminals = new ArrayList<>();
            for (String id : terminalIds) {
                Task t = taskRepo.getById(id);
                if (t != null) terminals.add(t);
            }

            if (terminals.isEmpty()) {
                return CompositeGoal.builder()
                        .root(spec)
                        .pointsGap(0)
                        .coveredBy(0)
                        .reachable(true)
                        .build();
            }

            Set<String> completed = ctx != null && ctx.getCompletedTasks() != null
                    ? ctx.getCompletedTasks() : Collections.emptySet();
            List<Task> dag = buildDag(terminals, completed);
            List<Task> sorted = topologicalSort(dag);

            log.info("BUILD goal '{}': {} terminal tasks, {} total after DAG expansion",
                    spec.getRawPhrase(), terminals.size(), sorted.size());

            return CompositeGoal.builder()
                    .root(spec)
                    .taskBatch(sorted)
                    .pointsGap(0)
                    .coveredBy(0)
                    .reachable(true)
                    .build();
        }

        // Pact goals: nothing to plan. Echo back as reachable with an empty
        // batch. ChatService fires the callback so the UI shows the goal and
        // the LLM can talk about the pact via prompt context.
        if (spec.getType() == GoalType.PACT) {
            return CompositeGoal.builder()
                    .root(spec)
                    .pointsGap(0)
                    .coveredBy(0)
                    .reachable(true)
                    .build();
        }

        // Relic / Area: cost gap driven. Zero cost triggers the unknown-cost
        // heuristic (see UNKNOWN_COST_TASK_CAP) instead of a short-circuit.
        int currentPoints = ctx != null ? ctx.getLeaguePoints() : 0;
        int unlockCost = spec.getUnlockCost();
        boolean costUnknown = unlockCost == 0;
        int gap;
        if (costUnknown) {
            // Force the loop to run to the cap instead of exiting on covered>=gap.
            gap = Integer.MAX_VALUE;
            log.info("Composite goal '{}' has unknown unlock cost, suggesting top {} achievable tasks",
                    spec.getRawPhrase(), UNKNOWN_COST_TASK_CAP);
        } else {
            gap = Math.max(0, unlockCost - currentPoints);
            if (gap == 0) {
                // Already affordable — the composite is "reachable now, no tasks
                // required". UI shows "Ready to unlock" state.
                return CompositeGoal.builder()
                        .root(spec)
                        .pointsGap(0)
                        .coveredBy(currentPoints)
                        .reachable(true)
                        .build();
            }
        }

        List<Task> achievable = filterAchievableTasks(ctx);
        // Sort by highest points-per-effort first so the greedy pick stays
        // short. Secondary sort by raw points so ties prefer the bigger
        // reward.
        achievable.sort((a, b) -> {
            double ra = pointsPerEffort(a);
            double rb = pointsPerEffort(b);
            int cmp = Double.compare(rb, ra);
            if (cmp != 0) return cmp;
            return Integer.compare(b.getPoints(), a.getPoints());
        });

        List<Task> picked = new ArrayList<>();
        int covered = 0;
        int cap = costUnknown ? UNKNOWN_COST_TASK_CAP : Integer.MAX_VALUE;
        for (Task t : achievable) {
            if (!costUnknown && covered >= gap) break;
            if (picked.size() >= cap) break;
            picked.add(t);
            covered += t.getPoints();
        }

        // In unknown-cost mode, "reachable" just means we picked something to
        // show the user. In cost-driven mode it means the picks cover the gap.
        boolean reachable = costUnknown ? !picked.isEmpty() : covered >= gap;
        List<GoalSpec> children = new ArrayList<>();

        if (!reachable && !costUnknown) {
            // Can't close the gap with achievable tasks alone. Figure out
            // which LOCKED area would contribute the most points if the
            // player unlocked it. Emit that as a child goal so the UI can
            // show a chain. Skip this in unknown-cost mode — suggesting an
            // area unlock as a prereq when the area's cost is ALSO unknown
            // produces a confusing cascade with no real information.
            GoalSpec areaPrereq = suggestAreaUnlock(ctx);
            if (areaPrereq != null) {
                children.add(areaPrereq);
            }
            log.info("Composite goal '{}' unreachable: gap={} covered={} child={}",
                    spec.getRawPhrase(), gap, covered,
                    areaPrereq != null ? areaPrereq.getTargetName() : "(none)");
        } else if (!costUnknown) {
            log.info("Composite goal '{}' reachable: gap={} picked={} tasks covered={}",
                    spec.getRawPhrase(), gap, picked.size(), covered);
        } else {
            log.info("Composite goal '{}' (unknown cost) picked {} tasks, total points={}",
                    spec.getRawPhrase(), picked.size(), covered);
        }

        return CompositeGoal.builder()
                .root(spec)
                .children(children)
                .taskBatch(picked)
                // In unknown-cost mode, reporting pointsGap = MAX_VALUE would
                // be confusing in logs + UI. Clamp to 0 so the semantics stay
                // "no explicit gap, here are some tasks."
                .pointsGap(costUnknown ? 0 : gap)
                .coveredBy(covered)
                .reachable(reachable)
                .build();
    }

    /**
     * Resolve an {@link GoalType#ITEM} goal by expanding the item dependency
     * graph and matching each node to any corresponding Leagues task.
     *
     * <p>Returns a {@link CompositeGoal} with a taskBatch of matched tasks,
     * ordered by prerequisite chain (deepest prereq first). If the graph has
     * no data for this item, returns an empty batch and lets the LLM fall back
     * to its own knowledge.
     */
    private CompositeGoal resolveItemGoal(GoalSpec spec, PlayerContext ctx) {
        String targetId = spec.getTargetId();
        if (targetId == null || itemDependencyGraph == null || itemDependencyGraph.isEmpty()) {
            return CompositeGoal.builder()
                    .root(spec)
                    .pointsGap(0)
                    .coveredBy(0)
                    .reachable(true)
                    .build();
        }

        List<com.leaguesai.data.model.ItemDependency> deps =
                itemDependencyGraph.expand(targetId);

        if (deps.isEmpty()) {
            // No dependency chain. Check if the item is in the equipment catalog — if so,
            // do an on-demand wiki lookup to find shop/NPC obtain locations and build
            // overlay-ready PlannedStep objects directly (no Task objects needed).
            if (itemDependencyGraph.isInCatalog(targetId) && wikiItemLookup != null) {
                return resolveItemViaWikiLookup(spec, targetId, ctx);
            }
            log.info("ITEM goal '{}': no dependency data and not in catalog, LLM fallback will fire",
                    spec.getTargetName());
            return CompositeGoal.builder()
                    .root(spec)
                    .pointsGap(0)
                    .coveredBy(0)
                    .reachable(true)
                    .build();
        }

        // For each dependency node, see if there's a matching Leagues task.
        Set<String> completed = ctx != null && ctx.getCompletedTasks() != null
                ? ctx.getCompletedTasks() : Collections.emptySet();
        List<Task> allTasks = taskRepo.getAllTasks();  // hoist: O(1) instead of O(deps.size())
        List<Task> matched = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (com.leaguesai.data.model.ItemDependency dep : deps) {
            List<Task> tasks = findByTargetItemName(dep.getItemName(), allTasks);
            if (tasks != null) {
                for (Task t : tasks) {
                    if (t != null && !seen.contains(t.getId()) && !completed.contains(t.getId())) {
                        seen.add(t.getId());
                        matched.add(t);
                    }
                }
            }
        }

        log.info("ITEM goal '{}': {} dep nodes → {} matched tasks",
                spec.getTargetName(), deps.size(), matched.size());

        // Build skilling steps for any deps that require levelling a skill first.
        Map<net.runelite.api.Skill, Integer> playerXp = ctx != null ? ctx.getXp() : null;
        List<PlannedStep> skillingSteps = SkillingStepBuilder.buildSkillingSteps(deps, playerXp);
        if (!skillingSteps.isEmpty()) {
            log.info("ITEM goal '{}': {} skilling steps added", spec.getTargetName(), skillingSteps.size());
        }

        return CompositeGoal.builder()
                .root(spec)
                .taskBatch(matched)
                .skillingSteps(skillingSteps)
                .pointsGap(0)
                .coveredBy(0)
                .reachable(true)
                .build();
    }

    /**
     * On-demand wiki lookup for items in the equipment catalog with no dependency chain.
     * Fetches the OSRS wiki wikitext, parses shop/NPC obtain locations, and builds
     * overlay-ready {@link PlannedStep}s that the plan callback can activate directly
     * without going through the Task pipeline.
     *
     * <p>Inventory pre-check: if the player already holds enough coins the bank step
     * is skipped. If they already hold the item itself the entire plan is skipped.
     */
    private CompositeGoal resolveItemViaWikiLookup(GoalSpec spec, String targetId, PlayerContext ctx) {
        String wikiUrl = itemDependencyGraph.getWikiUrl(targetId);
        String pageSlug = WikiItemLookup.pageSlugFromUrl(wikiUrl);
        int catalogGameId = itemDependencyGraph.getCatalogGameId(targetId);
        String displayName = spec.getTargetName() != null ? spec.getTargetName()
                : itemDependencyGraph.getCatalogDisplayName(targetId);

        // Inventory pre-check: if the player already has this item, no plan needed.
        Map<String, Integer> inventory = ctx != null && ctx.getInventory() != null
                ? ctx.getInventory() : Collections.emptyMap();
        if (displayName != null) {
            for (Map.Entry<String, Integer> inv : inventory.entrySet()) {
                if (inv.getKey() != null && inv.getKey().equalsIgnoreCase(displayName)
                        && inv.getValue() > 0) {
                    log.info("ITEM goal '{}': player already has it in inventory, skipping plan", displayName);
                    return CompositeGoal.builder()
                            .root(spec)
                            .pointsGap(0)
                            .coveredBy(0)
                            .reachable(true)
                            .build();
                }
            }
        }

        log.info("ITEM goal '{}': catalog hit, fetching wiki '{}'", displayName, pageSlug);

        List<WikiItemLookup.ShopEntry> shops;
        try {
            shops = wikiItemLookup.lookupShops(pageSlug, catalogGameId);
        } catch (Exception e) {
            log.warn("ITEM goal '{}': wiki lookup threw: {}", displayName, e.getMessage());
            shops = Collections.emptyList();
        }

        if (shops.isEmpty()) {
            log.info("ITEM goal '{}': wiki lookup found no shop entries, LLM fallback will fire", displayName);
            return CompositeGoal.builder()
                    .root(spec)
                    .pointsGap(0)
                    .coveredBy(0)
                    .reachable(true)
                    .build();
        }

        // Build PlannedSteps per shop entry.
        // Each entry gets TWO steps: (1) get coins from nearest bank, (2) buy from shop.
        List<PlannedStep> directSteps = new ArrayList<>();
        for (WikiItemLookup.ShopEntry shop : shops) {
            WorldPoint shopLocation = shop.getLocation();
            WorldPoint bankLocation = shop.getNearestBank();
            String npcName = shop.getNpcName();

            List<Integer> npcIds = shop.getNpcGameId() > 0
                    ? Collections.singletonList(shop.getNpcGameId())
                    : Collections.emptyList();
            List<String> npcNames = npcName != null
                    ? Collections.singletonList(npcName)
                    : Collections.emptyList();
            List<Integer> shopItemIds = shop.getItemGameId() > 0
                    ? Collections.singletonList(shop.getItemGameId())
                    : Collections.emptyList();

            /** OSRS item ID for coins (used in completion condition). */
            final int COINS_ID = 995;

            // Step 1: Withdraw coins from the nearest bank.
            // Skipped if the player already has enough coins in their inventory.
            // Auto-completes when coins (item 995) appear in the player's inventory.
            int coinsHeld = inventory.getOrDefault("Coins", 0);
            boolean needsCoins = bankLocation != null && coinsHeld < shop.getShopValue();
            if (needsCoins) {
                String coinsInstruction = buildCoinsInstruction(displayName, shop);
                OverlayData bankOverlay = OverlayData.builder()
                        .targetTile(bankLocation)
                        .targetNpcIds(Collections.emptyList())
                        .targetNpcNames(Collections.emptyList())
                        .targetObjectIds(Collections.emptyList())
                        .targetItemIds(Collections.emptyList())
                        .pathPoints(Collections.emptyList())
                        .widgetIds(Collections.emptyList())
                        .shopItemGameIds(Collections.emptyList())
                        .showArrow(true)
                        .showMinimap(true)
                        .showWorldMap(true)
                        .build();
                directSteps.add(PlannedStep.builder()
                        .task(null)
                        .destination(bankLocation)
                        .instruction(coinsInstruction)
                        .overlayData(bankOverlay)
                        .completionItemIds(Collections.singletonList(COINS_ID))
                        .completionItemMinQtys(Collections.singletonMap(COINS_ID, shop.getShopValue()))
                        .build());
            }

            // Step 2: Buy the item from the shop.
            // Auto-completes when the target item appears in the player's inventory.
            String shopInstruction = buildShopInstruction(displayName, shop);
            OverlayData shopOverlay = OverlayData.builder()
                    .targetTile(shopLocation)
                    .targetNpcIds(npcIds)
                    .targetNpcNames(npcNames)
                    .targetObjectIds(Collections.emptyList())
                    .targetItemIds(Collections.emptyList())
                    .pathPoints(Collections.emptyList())
                    .widgetIds(Collections.emptyList())
                    .shopItemGameIds(shopItemIds)
                    .showArrow(shopLocation != null)
                    .showMinimap(shopLocation != null)
                    .showWorldMap(shopLocation != null)
                    .build();
            directSteps.add(PlannedStep.builder()
                    .task(null)
                    .destination(shopLocation)
                    .instruction(shopInstruction)
                    .overlayData(shopOverlay)
                    .completionItemIds(shopItemIds.isEmpty()
                            ? Collections.emptyList() : shopItemIds)
                    .build());
        }

        log.info("ITEM goal '{}': built {} direct steps from wiki lookup", displayName, directSteps.size());

        return CompositeGoal.builder()
                .root(spec)
                .directSteps(directSteps)
                .pointsGap(0)
                .coveredBy(0)
                .reachable(true)
                .build();
    }

    /**
     * Resolve a {@link GoalType#CRAFT} goal: fetch the OSRS wiki crafting recipe,
     * then build overlay-ready {@link PlannedStep}s for each ingredient (bank +
     * buy, or locate if not sold in a shop) followed by a final crafting step.
     *
     * <p>Inventory pre-check: materials already held by the player are skipped;
     * if the player already has the finished item, the plan is skipped entirely.
     */
    private CompositeGoal resolveCraftGoal(GoalSpec spec, PlayerContext ctx) {
        if (wikiItemLookup == null) return emptyReachable(spec);

        String targetName = spec.getTargetName();

        // Resolve the craftable wiki slug: check alias table first, else use item name.
        String craftSlug = WikiItemLookup.craftAliasSlug(targetName);
        if (craftSlug == null) {
            craftSlug = targetName != null ? targetName.replace(' ', '_') : null;
        }
        if (craftSlug == null) return emptyReachable(spec);

        // Inventory snapshot for pre-checks.
        Map<String, Integer> inventory = ctx != null && ctx.getInventory() != null
                ? ctx.getInventory() : Collections.emptyMap();

        // If the player already holds this item, skip the whole plan.
        String finalCraftSlug = craftSlug;
        String craftedDisplayName = craftSlug.replace('_', ' ');
        for (Map.Entry<String, Integer> inv : inventory.entrySet()) {
            if (inv.getKey() != null && inv.getKey().equalsIgnoreCase(craftedDisplayName)
                    && inv.getValue() > 0) {
                log.info("CRAFT goal '{}': player already has '{}', skipping plan",
                        targetName, craftedDisplayName);
                return emptyReachable(spec);
            }
        }

        // Fetch crafting recipe from the wiki.
        WikiItemLookup.CraftingRecipe recipe;
        try {
            recipe = wikiItemLookup.lookupCraftingRecipe(finalCraftSlug);
        } catch (Exception e) {
            log.warn("CRAFT goal '{}': wiki lookup failed: {}", targetName, e.getMessage());
            return emptyReachable(spec);
        }

        if (recipe == null || recipe.getMaterials().isEmpty()) {
            log.info("CRAFT goal '{}': no recipe found for '{}', LLM fallback", targetName, craftSlug);
            return emptyReachable(spec);
        }

        List<PlannedStep> directSteps = new ArrayList<>();
        final int COINS_ID = 995;

        for (WikiItemLookup.MaterialEntry mat : recipe.getMaterials()) {
            String matName = mat.getItemName();

            // Skip materials already in inventory.
            boolean haveIt = false;
            for (Map.Entry<String, Integer> inv : inventory.entrySet()) {
                if (inv.getKey() != null && inv.getKey().equalsIgnoreCase(matName)
                        && inv.getValue() >= mat.getQuantity()) {
                    haveIt = true;
                    break;
                }
            }
            if (haveIt) {
                log.info("CRAFT goal '{}': skipping '{}' (already in inventory)", targetName, matName);
                continue;
            }

            // Try shop lookup for this material.
            String matSlug = matName.replace(' ', '_');
            List<WikiItemLookup.ShopEntry> shops = Collections.emptyList();
            try {
                shops = wikiItemLookup.lookupShops(matSlug, 0);
            } catch (Exception ignored) { /* fall through to obtain-location path */ }

            if (!shops.isEmpty()) {
                WikiItemLookup.ShopEntry shop = shops.get(0);
                WorldPoint shopLocation = shop.getLocation();
                WorldPoint bankLocation = shop.getNearestBank();
                String npcName = shop.getNpcName();
                List<Integer> npcIds = shop.getNpcGameId() > 0
                        ? Collections.singletonList(shop.getNpcGameId()) : Collections.emptyList();
                List<String> npcNames = npcName != null
                        ? Collections.singletonList(npcName) : Collections.emptyList();
                List<Integer> shopItemIds = shop.getItemGameId() > 0
                        ? Collections.singletonList(shop.getItemGameId()) : Collections.emptyList();

                // Bank step (skip if player already has enough coins)
                int coinsHeld = 0;
                for (Map.Entry<String, Integer> inv : inventory.entrySet()) {
                    if ("Coins".equalsIgnoreCase(inv.getKey())) { coinsHeld = inv.getValue(); break; }
                }
                if (bankLocation != null && coinsHeld < shop.getShopValue()) {
                    String coinsInstruction = "Withdraw coins from bank (need ~"
                            + String.format("%,d", shop.getShopValue())
                            + " coins for " + matName + ")";
                    directSteps.add(PlannedStep.builder()
                            .task(null)
                            .destination(bankLocation)
                            .instruction(coinsInstruction)
                            .overlayData(OverlayData.builder()
                                    .targetTile(bankLocation)
                                    .targetNpcIds(Collections.emptyList())
                                    .targetNpcNames(Collections.emptyList())
                                    .targetObjectIds(Collections.emptyList())
                                    .targetItemIds(Collections.emptyList())
                                    .pathPoints(Collections.emptyList())
                                    .widgetIds(Collections.emptyList())
                                    .shopItemGameIds(Collections.emptyList())
                                    .showArrow(true).showMinimap(true).showWorldMap(true)
                                    .build())
                            .completionItemIds(Collections.singletonList(COINS_ID))
                            .completionItemMinQtys(Collections.singletonMap(COINS_ID, shop.getShopValue()))
                            .itemSourceNotes(Collections.emptyMap())
                            .build());
                }

                // Buy step
                String buyInstruction = "Buy " + matName + " from "
                        + (npcName != null ? npcName : "shop")
                        + " (" + shop.getShopName() + ")"
                        + (shop.getShopValue() > 0
                            ? " — ~" + String.format("%,d", shop.getShopValue()) + " coins" : "");
                directSteps.add(PlannedStep.builder()
                        .task(null)
                        .destination(shopLocation)
                        .instruction(buyInstruction)
                        .overlayData(OverlayData.builder()
                                .targetTile(shopLocation)
                                .targetNpcIds(npcIds)
                                .targetNpcNames(npcNames)
                                .targetObjectIds(Collections.emptyList())
                                .targetItemIds(Collections.emptyList())
                                .pathPoints(Collections.emptyList())
                                .widgetIds(Collections.emptyList())
                                .shopItemGameIds(shopItemIds)
                                .showArrow(shopLocation != null)
                                .showMinimap(shopLocation != null)
                                .showWorldMap(shopLocation != null)
                                .build())
                        .completionItemIds(shopItemIds.isEmpty() ? Collections.emptyList() : shopItemIds)
                        .itemSourceNotes(Collections.emptyMap())
                        .build());

            } else {
                // Non-shop material: point the player at the known obtain location.
                WorldPoint obtainLoc = WikiItemLookup.getObtainLocation(matName.toLowerCase(Locale.ROOT));
                String instruction = "Obtain " + matName + " ×" + mat.getQuantity();
                directSteps.add(PlannedStep.builder()
                        .task(null)
                        .destination(obtainLoc)
                        .instruction(instruction)
                        .overlayData(OverlayData.builder()
                                .targetTile(obtainLoc)
                                .targetNpcIds(Collections.emptyList())
                                .targetNpcNames(Collections.emptyList())
                                .targetObjectIds(Collections.emptyList())
                                .targetItemIds(Collections.emptyList())
                                .pathPoints(Collections.emptyList())
                                .widgetIds(Collections.emptyList())
                                .shopItemGameIds(Collections.emptyList())
                                .showArrow(obtainLoc != null)
                                .showMinimap(obtainLoc != null)
                                .showWorldMap(obtainLoc != null)
                                .build())
                        .completionItemIds(Collections.emptyList())
                        .itemSourceNotes(Collections.emptyMap())
                        .build());
            }
        }

        // Final crafting step.
        WorldPoint craftLoc = recipe.getCraftingLocation();
        String craftInstruction = "Craft " + recipe.getCraftedItem()
                + " (" + recipe.getSkill() + " lv." + recipe.getLevelRequired()
                + ", " + String.format("%.1f", recipe.getXpGained()) + " xp)"
                + " — " + recipe.getCraftingLocationName();
        directSteps.add(PlannedStep.builder()
                .task(null)
                .destination(craftLoc)
                .instruction(craftInstruction)
                .overlayData(OverlayData.builder()
                        .targetTile(craftLoc)
                        .targetNpcIds(Collections.emptyList())
                        .targetNpcNames(Collections.emptyList())
                        .targetObjectIds(Collections.emptyList())
                        .targetItemIds(Collections.emptyList())
                        .pathPoints(Collections.emptyList())
                        .widgetIds(Collections.emptyList())
                        .shopItemGameIds(Collections.emptyList())
                        .showArrow(craftLoc != null)
                        .showMinimap(craftLoc != null)
                        .showWorldMap(craftLoc != null)
                        .build())
                .completionItemIds(Collections.emptyList())
                .itemSourceNotes(Collections.emptyMap())
                .build());

        log.info("CRAFT goal '{}': built {} steps (recipe: {} {}lvl, mats: {})",
                targetName, directSteps.size(), recipe.getCraftedItem(),
                recipe.getLevelRequired(), recipe.getMaterials().size());

        return CompositeGoal.builder()
                .root(spec)
                .directSteps(directSteps)
                .pointsGap(0)
                .coveredBy(0)
                .reachable(true)
                .build();
    }

    /** Helper: empty reachable composite for graceful fallback to LLM. */
    private static CompositeGoal emptyReachable(GoalSpec spec) {
        return CompositeGoal.builder()
                .root(spec)
                .pointsGap(0)
                .coveredBy(0)
                .reachable(true)
                .build();
    }

    private static String buildShopInstruction(String itemName, WikiItemLookup.ShopEntry shop) {
        StringBuilder sb = new StringBuilder("Buy ");
        sb.append(itemName != null ? itemName : "item");
        if (shop.getNpcName() != null) {
            sb.append(" from ").append(shop.getNpcName());
        }
        if (shop.getShopName() != null) {
            sb.append(" (").append(shop.getShopName()).append(")");
        }
        if (shop.getShopValue() > 0) {
            sb.append(" — ~").append(String.format("%,d", shop.getShopValue())).append(" coins");
        }
        return sb.toString();
    }

    private static String buildCoinsInstruction(String itemName, WikiItemLookup.ShopEntry shop) {
        StringBuilder sb = new StringBuilder("Withdraw coins from bank");
        if (shop.getShopValue() > 0) {
            sb.append(" (need ~").append(String.format("%,d", shop.getShopValue())).append(" coins");
            sb.append(" for ").append(itemName != null ? itemName : "item").append(")");
        } else {
            sb.append(" (need coins to buy ").append(itemName != null ? itemName : "item").append(")");
        }
        return sb.toString();
    }

    /**
     * Finds tasks whose {@code targetItems} list contains an item with the given
     * display name (case-insensitive substring match).
     *
     * @param allTasks pre-fetched task list — callers must hoist {@code taskRepo.getAllTasks()}
     *                 outside any loop to avoid O(n) lookups per invocation.
     */
    private List<Task> findByTargetItemName(String itemName, List<Task> allTasks) {
        if (itemName == null || allTasks == null) return Collections.emptyList();
        String lower = itemName.toLowerCase();
        List<Task> result = new ArrayList<>();
        for (Task t : allTasks) {
            if (t.getTargetItems() != null) {
                for (com.leaguesai.data.model.Task.ItemTarget it : t.getTargetItems()) {
                    if (it != null && it.getName() != null
                            && it.getName().toLowerCase().contains(lower)) {
                        result.add(t);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Filter {@code taskRepo.getAllTasks()} to tasks the player can actually
     * do right now: not already completed, required skill levels met, in an
     * area the player has unlocked (or area-agnostic tasks with a null area).
     */
    private List<Task> filterAchievableTasks(PlayerContext ctx) {
        List<Task> all = taskRepo.getAllTasks();
        if (all == null || all.isEmpty()) return new ArrayList<>();

        Set<String> completed = ctx != null && ctx.getCompletedTasks() != null
                ? ctx.getCompletedTasks() : Collections.emptySet();
        Set<String> unlockedAreas = ctx != null && ctx.getUnlockedAreas() != null
                ? ctx.getUnlockedAreas() : Collections.emptySet();
        Map<Skill, Integer> levels = ctx != null && ctx.getLevels() != null
                ? ctx.getLevels() : Collections.emptyMap();

        List<Task> out = new ArrayList<>();
        for (Task t : all) {
            if (t == null || t.getId() == null) continue;
            if (completed.contains(t.getId())) continue;
            if (t.getArea() != null && !unlockedAreas.isEmpty()
                    && !unlockedAreas.contains(t.getArea())) {
                continue;
            }
            if (!skillsMet(t, levels)) continue;
            out.add(t);
        }
        return out;
    }

    /**
     * Check every entry in {@code task.skillsRequired} against the player's
     * current levels. If the player is short on any, the task is excluded.
     *
     * <p><b>Fail-closed on unknown skill names.</b> {@code TaskNormalizer}
     * normalises wiki skill names to RuneLite {@code Skill} enum values at
     * scrape time. But if the database has stale rows from before that
     * normalization landed (e.g., a {@code "runecrafting"} key that should be
     * {@code "runecraft"}), we must NOT silently skip the requirement. Doing
     * so recommends unachievable tasks to players who then burn a trip. So we
     * treat an unresolvable skill name as "requirement not met" and exclude
     * the task. User sees a shorter plan, not a ghost recommendation.
     */
    private boolean skillsMet(Task task, Map<Skill, Integer> levels) {
        Map<String, Integer> required = task.getSkillsRequired();
        if (required == null || required.isEmpty()) return true;
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            Skill skill;
            try {
                skill = Skill.valueOf(entry.getKey().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.debug("Unknown skill name in task {} requirements: '{}' — excluding task",
                        task.getId(), entry.getKey());
                return false;
            }
            Integer have = levels.get(skill);
            if (have == null || have < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Points-per-effort where effort = difficulty tier. Easy tasks are 1
     * effort, master tasks are 5. A master task giving 100 points scores
     * 20, an easy task giving 20 points scores 20 — roughly comparable.
     * Tasks with unknown difficulty get effort 3 (medium) to avoid surfacing
     * them as highest or lowest priority.
     */
    private static double pointsPerEffort(Task t) {
        int effort = t.getDifficulty() != null ? t.getDifficulty().getTier() : 3;
        if (effort <= 0) effort = 1;
        return (double) t.getPoints() / effort;
    }

    /**
     * Pick the best area to suggest unlocking when no currently-achievable
     * task set closes the gap. "Best" = the locked area whose task list
     * contributes the most cumulative points. Returns {@code null} if every
     * area is already unlocked (i.e. the gap is genuinely unreachable
     * because of skill / quest gates, not area gating).
     */
    private GoalSpec suggestAreaUnlock(PlayerContext ctx) {
        if (ctx == null) return null;
        List<Area> areas = taskRepo.getAllAreas();
        if (areas == null || areas.isEmpty()) return null;
        Set<String> unlocked = ctx.getUnlockedAreas() != null
                ? ctx.getUnlockedAreas() : Collections.emptySet();

        Area best = null;
        int bestPoints = -1;
        for (Area a : areas) {
            if (a == null || a.getId() == null) continue;
            if (unlocked.contains(a.getId()) || unlocked.contains(a.getName())) continue;
            int total = 0;
            for (Task t : taskRepo.getByArea(a.getId())) {
                total += t.getPoints();
            }
            if (total > bestPoints) {
                bestPoints = total;
                best = a;
            }
        }
        if (best == null) return null;
        return GoalSpec.builder()
                .type(GoalType.AREA)
                .targetId(best.getId())
                .targetName(best.getName())
                .rawPhrase("plan unlock " + best.getName())
                .unlockCost(best.getUnlockCost())
                .build();
    }

    /**
     * Simple keyword matching for area + difficulty. If goal contains an area name,
     * return tasks in that area. If it also contains "easy"/"medium"/etc., filter
     * by difficulty. LLM disambiguation happens at a higher layer.
     */
    public List<Task> resolveGoalTasks(String goal) {
        if (goal == null || goal.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String lowerGoal = goal.toLowerCase();

        // Determine difficulty filter if present
        Difficulty difficultyFilter = null;
        for (Difficulty d : Difficulty.values()) {
            if (lowerGoal.contains(d.name().toLowerCase())) {
                difficultyFilter = d;
                break;
            }
        }

        // Collect matching tasks by area keyword
        List<Task> allTasks = taskRepo.getAllTasks();
        if (allTasks == null) return Collections.emptyList();

        List<Task> results = new ArrayList<>();
        for (Task task : allTasks) {
            if (task.getArea() != null && lowerGoal.contains(task.getArea().toLowerCase())) {
                if (difficultyFilter == null || difficultyFilter.equals(task.getDifficulty())) {
                    results.add(task);
                }
            }
        }

        return results;
    }
}
