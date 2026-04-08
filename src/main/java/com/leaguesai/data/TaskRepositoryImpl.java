package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

public class TaskRepositoryImpl implements TaskRepository {

    private final Map<String, Task> tasksById;
    private final Map<String, Area> areasById;
    private final Map<String, Relic> relicsById;
    private final Map<String, Pact> pactsById;

    /**
     * Back-compat constructor — no relics/pacts. Prefer the 4-arg overload
     * in new code so relic/pact goal-picker UI has data to render.
     */
    public TaskRepositoryImpl(List<Task> tasks, List<Area> areas) {
        this(tasks, areas, Collections.emptyList(), Collections.emptyList());
    }

    public TaskRepositoryImpl(List<Task> tasks, List<Area> areas,
                              List<Relic> relics, List<Pact> pacts) {
        tasksById = new HashMap<>();
        for (Task task : tasks) {
            tasksById.put(task.getId(), task);
        }

        areasById = new HashMap<>();
        for (Area area : areas) {
            areasById.put(area.getId(), area);
        }

        // LinkedHashMap preserves scraper insertion order so the UI can
        // render relics in wiki-declared tier order without re-sorting.
        relicsById = new LinkedHashMap<>();
        if (relics != null) {
            for (Relic r : relics) {
                if (r != null && r.getId() != null) {
                    relicsById.put(r.getId(), r);
                }
            }
        }

        pactsById = new LinkedHashMap<>();
        if (pacts != null) {
            for (Pact p : pacts) {
                if (p != null && p.getId() != null) {
                    pactsById.put(p.getId(), p);
                }
            }
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

    @Override
    public List<Relic> getAllRelics() {
        return new ArrayList<>(relicsById.values());
    }

    @Override
    public List<Pact> getAllPacts() {
        return new ArrayList<>(pactsById.values());
    }

    @Override
    public Optional<Relic> findRelicByName(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        String needle = name.toLowerCase();
        // Two-pass: exact match first, then substring. Prevents "dragon" from
        // shadowing an exact-name "Dragon" relic when another relic is named
        // "Dragon Slayer" etc.
        for (Relic r : relicsById.values()) {
            if (r.getName() != null && r.getName().equalsIgnoreCase(name)) {
                return Optional.of(r);
            }
        }
        for (Relic r : relicsById.values()) {
            if (r.getName() != null && r.getName().toLowerCase().contains(needle)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Area> findAreaByName(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        String needle = name.toLowerCase();
        for (Area a : areasById.values()) {
            if (a.getName() != null && a.getName().equalsIgnoreCase(name)) {
                return Optional.of(a);
            }
            // Phase 1 areas use the name as the id (e.g. "Karamja"), so match
            // against the id too — the panel sends `plan unlock Karamja`.
            if (a.getId() != null && a.getId().equalsIgnoreCase(name)) {
                return Optional.of(a);
            }
        }
        for (Area a : areasById.values()) {
            if (a.getName() != null && a.getName().toLowerCase().contains(needle)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Pact> findPactByName(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        String needle = name.toLowerCase();
        for (Pact p : pactsById.values()) {
            if (p.getName() != null && p.getName().equalsIgnoreCase(name)) {
                return Optional.of(p);
            }
            if (p.getId() != null && p.getId().equalsIgnoreCase(name)) {
                return Optional.of(p);
            }
        }
        for (Pact p : pactsById.values()) {
            if (p.getName() != null && p.getName().toLowerCase().contains(needle)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
