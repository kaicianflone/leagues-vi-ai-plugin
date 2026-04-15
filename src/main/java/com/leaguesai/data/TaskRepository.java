package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Returns all tasks whose {@code targetItems} list contains the given
     * wiki item id. Uses in-memory filtering on already-parsed
     * {@link Task.ItemTarget#getId()} values so there is no prefix-collision
     * risk (e.g. id 657 will never match id 6570).
     */
    List<Task> findByTargetItemId(int wikiItemId);

    /**
     * Returns tasks matching all supplied filters. Null / empty values mean
     * "no filter on that dimension". Comparison is case-insensitive for area.
     *
     * @param area        exact area slug to match, or null for all areas
     * @param difficulties set of Difficulty values to include, or null/empty for all
     * @param pactOnly    if true, only return tasks whose category is "pact"
     * @param offset      0-based page offset (multiply by limit for pagination)
     * @param limit       max results to return
     */
    List<Task> findFiltered(String area, Set<Difficulty> difficulties,
                            boolean pactOnly, int offset, int limit);
}
