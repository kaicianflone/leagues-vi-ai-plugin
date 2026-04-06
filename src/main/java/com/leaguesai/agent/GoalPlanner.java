package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

@Singleton
public class GoalPlanner {

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
