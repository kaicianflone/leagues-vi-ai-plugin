package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

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

    @Inject
    public GoalPlanner(TaskRepository taskRepo) {
        this.taskRepo = taskRepo;
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
