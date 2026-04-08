package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;

import java.util.List;
import java.util.Optional;

public interface TaskRepository {
    List<Task> getAllTasks();
    Task getById(String id);
    List<Task> getByArea(String area);
    List<Task> getByDifficulty(Difficulty difficulty);
    List<Task> getPrerequisites(String taskId);
    List<Task> getAllPrerequisites(String taskId);
    List<Area> getAllAreas();
    Area getAreaByRegionId(int regionId);

    /** All relics loaded from the {@code relics} table, in insertion order. */
    List<Relic> getAllRelics();

    /** All pacts loaded from the {@code pacts} table, in insertion order. */
    List<Pact> getAllPacts();

    /**
     * Case-insensitive name lookup. Returns the first relic whose name matches
     * the given phrase as a substring or exact match. Used by
     * {@code GoalSpecParser} to turn a phrase like "Grimoire" from
     * {@code UnlockablesPanel} into a concrete relic.
     */
    Optional<Relic> findRelicByName(String name);

    /** Case-insensitive name lookup (exact or substring). */
    Optional<Area> findAreaByName(String name);

    /** Case-insensitive name lookup (exact or substring). */
    Optional<Pact> findPactByName(String name);
}
