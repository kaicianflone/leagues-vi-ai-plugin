package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import net.runelite.api.coords.WorldPoint;

import java.util.*;

public class PlannerOptimizer {

    private PlannerOptimizer() {}

    /**
     * Orders tasks by proximity using a greedy nearest-neighbor walk.
     *
     * Each task is assigned a representative location:
     *   1. The scraped precise WorldPoint (t.getLocation()) if present
     *   2. Otherwise the area hub from {@link AreaHubs}
     *   3. Otherwise no location (tasks with no location are appended at the end)
     *
     * The walk starts from {@code playerLocation} (or the first task if null),
     * then repeatedly picks the closest unvisited task. This naturally interleaves
     * difficulty tiers by geography — easy and medium tasks near each other are
     * visited together rather than all easys then all mediums.
     *
     * O(n²) — acceptable for plan sizes up to a few hundred tasks.
     */
    public static List<Task> optimizeOrder(List<Task> tasks, WorldPoint playerLocation) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }

        // Partition: located (have a representative point) vs. unlocated (append at end)
        List<Task> located = new ArrayList<>();
        List<Task> unlocated = new ArrayList<>();
        for (Task t : tasks) {
            WorldPoint rep = AreaHubs.resolve(t.getLocation(), t.getArea());
            if (rep != null) {
                located.add(t);
            } else {
                unlocated.add(t);
            }
        }

        if (located.isEmpty()) {
            // Nothing to reorder — return original order
            return new ArrayList<>(tasks);
        }

        // Pre-compute representative WorldPoints once to avoid O(n²) resolve calls.
        WorldPoint[] reps = new WorldPoint[located.size()];
        for (int i = 0; i < located.size(); i++) {
            reps[i] = AreaHubs.resolve(located.get(i).getLocation(), located.get(i).getArea());
        }

        // Nearest-neighbor greedy walk over located tasks
        List<Task> ordered = new ArrayList<>(located.size());
        boolean[] visited = new boolean[located.size()];

        // Starting position: player location, or the first task's location if unknown
        WorldPoint current = playerLocation != null ? playerLocation : reps[0];

        for (int step = 0; step < located.size(); step++) {
            int nearestIdx = -1;
            double nearestDist = Double.MAX_VALUE;

            for (int i = 0; i < located.size(); i++) {
                if (visited[i] || reps[i] == null) continue;
                double dist = distSq(current, reps[i]);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestIdx = i;
                }
            }

            if (nearestIdx < 0) break; // shouldn't happen
            visited[nearestIdx] = true;
            ordered.add(located.get(nearestIdx));
            if (reps[nearestIdx] != null) current = reps[nearestIdx];
        }

        // Append tasks without any location data at the end
        ordered.addAll(unlocated);
        return ordered;
    }

    private static double distSq(WorldPoint a, WorldPoint b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        return dx * dx + dy * dy;
    }
}
