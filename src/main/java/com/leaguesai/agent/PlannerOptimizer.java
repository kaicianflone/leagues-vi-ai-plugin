package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import net.runelite.api.coords.WorldPoint;

import java.util.*;

public class PlannerOptimizer {

    private PlannerOptimizer() {
        // Static utility class — not instantiable
    }

    /**
     * Optimizes task order by:
     * 1. Grouping tasks by area
     * 2. Within each group, sorting by difficulty tier ascending (easy first)
     * 3. Ordering groups by distance from playerLocation (nearest first)
     * 4. Flattening and returning the result
     *
     * Null-safe: tasks with no location are placed last.
     * If playerLocation is null, distance sort is skipped.
     */
    public static List<Task> optimizeOrder(List<Task> tasks, WorldPoint playerLocation) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }

        // Step 1: Group by area
        LinkedHashMap<String, List<Task>> byArea = new LinkedHashMap<>();
        for (Task task : tasks) {
            String area = task.getArea() != null ? task.getArea() : "";
            byArea.computeIfAbsent(area, k -> new ArrayList<>()).add(task);
        }

        // Step 2: Within each group, sort by difficulty tier ascending
        for (List<Task> group : byArea.values()) {
            group.sort(Comparator.comparingInt(t -> {
                if (t.getDifficulty() == null) return Integer.MAX_VALUE;
                return t.getDifficulty().getTier();
            }));
        }

        // Step 3: Order groups by distance from playerLocation (nearest first)
        // If playerLocation is null, skip distance sort
        List<Map.Entry<String, List<Task>>> areaEntries = new ArrayList<>(byArea.entrySet());

        if (playerLocation != null) {
            areaEntries.sort(Comparator.comparingDouble(entry -> {
                List<Task> group = entry.getValue();
                // Use the first task with a non-null location as the group's representative
                WorldPoint firstLoc = null;
                for (Task t : group) {
                    if (t.getLocation() != null) {
                        firstLoc = t.getLocation();
                        break;
                    }
                }
                // Tasks with no location go last
                if (firstLoc == null) return Double.MAX_VALUE;
                return firstLoc.distanceTo(playerLocation);
            }));
        }

        // Step 4: Flatten and return
        List<Task> result = new ArrayList<>();
        for (Map.Entry<String, List<Task>> entry : areaEntries) {
            result.addAll(entry.getValue());
        }

        return result;
    }
}
