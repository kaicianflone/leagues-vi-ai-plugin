package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

public class TaskRepositoryImpl implements TaskRepository {

    private final Map<String, Task> tasksById;
    private final Map<String, Area> areasById;

    public TaskRepositoryImpl(List<Task> tasks, List<Area> areas) {
        tasksById = new HashMap<>();
        for (Task task : tasks) {
            tasksById.put(task.getId(), task);
        }

        areasById = new HashMap<>();
        for (Area area : areas) {
            areasById.put(area.getId(), area);
        }
    }

    @Override
    public List<Task> getAllTasks() {
        return new ArrayList<>(tasksById.values());
    }

    @Override
    public Task getById(String id) {
        return tasksById.get(id);
    }

    @Override
    public List<Task> getByArea(String area) {
        return tasksById.values().stream()
                .filter(t -> area != null && area.equals(t.getArea()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> getByDifficulty(Difficulty difficulty) {
        return tasksById.values().stream()
                .filter(t -> difficulty != null && difficulty.equals(t.getDifficulty()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> getPrerequisites(String taskId) {
        Task task = tasksById.get(taskId);
        if (task == null || task.getTasksRequired() == null) {
            return Collections.emptyList();
        }
        List<Task> prereqs = new ArrayList<>();
        for (String prereqId : task.getTasksRequired()) {
            Task prereq = tasksById.get(prereqId);
            if (prereq != null) {
                prereqs.add(prereq);
            }
        }
        return prereqs;
    }

    @Override
    public List<Task> getAllPrerequisites(String taskId) {
        List<Task> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        // Seed with direct prerequisites of the given task.
        // Mark the root as visited first to guard against cycles pointing back to it.
        Task root = tasksById.get(taskId);
        if (root == null || root.getTasksRequired() == null) {
            return result;
        }
        visited.add(taskId);
        for (String id : root.getTasksRequired()) {
            if (!visited.contains(id)) {
                visited.add(id);
                queue.add(id);
            }
        }

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            Task current = tasksById.get(currentId);
            if (current == null) {
                continue;
            }
            result.add(current);

            if (current.getTasksRequired() != null) {
                for (String nextId : current.getTasksRequired()) {
                    if (!visited.contains(nextId)) {
                        visited.add(nextId);
                        queue.add(nextId);
                    }
                }
            }
        }

        return result;
    }

    @Override
    public List<Area> getAllAreas() {
        return new ArrayList<>(areasById.values());
    }

    @Override
    public Area getAreaByRegionId(int regionId) {
        for (Area area : areasById.values()) {
            if (area.getRegionIds() != null && area.getRegionIds().contains(regionId)) {
                return area;
            }
        }
        return null;
    }
}
