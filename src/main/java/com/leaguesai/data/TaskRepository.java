package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;

import java.util.List;

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
}
