package com.leaguesai.agent;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Singleton;
import java.util.*;

/**
 * Relic-aware nearest-neighbor reordering of a plan's {@link PlannedStep} list.
 *
 * <p>The existing {@link PlannerOptimizer} sorts tasks by area-distance at the
 * {@code Task} level. This class applies a second-pass optimization at the
 * {@code PlannedStep} level, after overlay data is attached, using:
 *
 * <ol>
 *   <li><b>Topological level grouping</b> — tasks whose prerequisites all
 *       appear in earlier levels are in the same level and can be freely
 *       reordered among themselves without violating dependency constraints.</li>
 *   <li><b>Nearest-neighbour within each level</b> — minimises total walk
 *       distance by always picking the closest remaining task.</li>
 *   <li><b>Relic teleport cost model</b> — for each unlocked area/relic id,
 *       {@link RelicTeleportData} provides teleport destination WorldPoints.
 *       These are treated as cost-zero fast-travel hubs: the effective cost
 *       to reach a task is {@code min(walk_from_current, walk_from_any_relic_dest)}.
 *       </li>
 * </ol>
 *
 * <p>Null-safe throughout: steps with no {@link PlannedStep#getDestination()} are
 * appended last within their level. A null {@link PlayerContext#getLocation()} is
 * treated as unknown and falls back to relic-teleport-only cost estimation.
 */
@Slf4j
@Singleton
public class ProximityOptimizer {

    /**
     * Re-orders {@code steps} in-place (returns a new list) respecting
     * dependency order while minimising travel distance.
     *
     * @param steps          the topologically-sorted planned steps produced by
     *                       {@link GoalPlanner}
     * @param ctx            player context (used for current location); nullable
     * @param unlockedIds    set of area/relic ids the player has unlocked;
     *                       each id is looked up in {@link RelicTeleportData}
     *                       to obtain free-travel waypoints
     * @return new list with the same elements in optimised order
     */
    public List<PlannedStep> optimize(List<PlannedStep> steps,
                                      PlayerContext ctx,
                                      Set<String> unlockedIds) {
        if (steps == null || steps.size() <= 1) {
            return steps == null ? Collections.emptyList() : new ArrayList<>(steps);
        }

        // --- 1. Build task-id → list-index lookup ---
        Map<String, Integer> idToIndex = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            PlannedStep s = steps.get(i);
            if (s != null && s.getTask() != null && s.getTask().getId() != null) {
                idToIndex.put(s.getTask().getId(), i);
            }
        }

        // --- 2. Assign topological levels ---
        // level[i] = max(level[prereq] + 1) for each prereq of step i that
        // appears in the plan; 0 when no in-plan prerequisites exist.
        int[] levels = new int[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            PlannedStep s = steps.get(i);
            if (s == null || s.getTask() == null || s.getTask().getTasksRequired() == null) {
                continue;
            }
            for (String prereqId : s.getTask().getTasksRequired()) {
                Integer prereqIdx = idToIndex.get(prereqId);
                if (prereqIdx != null) {
                    levels[i] = Math.max(levels[i], levels[prereqIdx] + 1);
                }
            }
        }

        // --- 3. Group steps by level (TreeMap preserves ascending level order) ---
        TreeMap<Integer, List<Integer>> levelToIndices = new TreeMap<>();
        for (int i = 0; i < steps.size(); i++) {
            levelToIndices.computeIfAbsent(levels[i], k -> new ArrayList<>()).add(i);
        }

        // --- 4. Collect free-travel WorldPoints from unlocked relics/areas ---
        List<WorldPoint> freeDests = new ArrayList<>();
        if (unlockedIds != null) {
            for (String id : unlockedIds) {
                freeDests.addAll(RelicTeleportData.getDestinations(id));
            }
        }

        // --- 5. Nearest-neighbour within each level ---
        WorldPoint currentPos = (ctx != null) ? ctx.getLocation() : null;
        List<PlannedStep> result = new ArrayList<>(steps.size());

        for (Map.Entry<Integer, List<Integer>> entry : levelToIndices.entrySet()) {
            // Work on a mutable copy of the index list for this level
            List<Integer> remaining = new ArrayList<>(entry.getValue());

            while (!remaining.isEmpty()) {
                int bestListIdx = pickNearest(steps, remaining, currentPos, freeDests);
                PlannedStep chosen = steps.get(remaining.remove(bestListIdx));
                result.add(chosen);
                if (chosen != null && chosen.getDestination() != null) {
                    currentPos = chosen.getDestination();
                }
            }
        }

        log.debug("ProximityOptimizer: reordered {} steps ({} levels, {} relic teleport hubs)",
                result.size(), levelToIndices.size(), freeDests.size());
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the position within {@code remaining} (not the step index) of the
     * step with the minimum effective travel cost.
     * Steps with no destination are appended last (cost = MAX_VALUE).
     */
    private static int pickNearest(List<PlannedStep> steps,
                                   List<Integer> remaining,
                                   WorldPoint currentPos,
                                   List<WorldPoint> freeDests) {
        int bestListIdx = 0;
        double bestCost = Double.MAX_VALUE;

        for (int listIdx = 0; listIdx < remaining.size(); listIdx++) {
            PlannedStep candidate = steps.get(remaining.get(listIdx));
            if (candidate == null) continue;
            WorldPoint dest = candidate.getDestination();
            if (dest == null) continue; // no-location tasks go last

            double cost = effectiveCost(currentPos, dest, freeDests);
            if (cost < bestCost) {
                bestCost = cost;
                bestListIdx = listIdx;
            }
        }
        return bestListIdx;
    }

    /**
     * Effective travel cost = {@code min(walk_from_current, walk_from_any_free_dest)}.
     * Free destinations (relic teleports) have zero access cost, so only the
     * walk from the teleport exit to the task destination counts.
     */
    private static double effectiveCost(WorldPoint from,
                                        WorldPoint to,
                                        List<WorldPoint> freeDests) {
        double direct = euclidean(from, to);
        double viaTeleport = Double.MAX_VALUE;
        for (WorldPoint fp : freeDests) {
            double d = euclidean(fp, to);
            if (d < viaTeleport) viaTeleport = d;
        }
        return Math.min(direct, viaTeleport);
    }

    private static double euclidean(WorldPoint a, WorldPoint b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
