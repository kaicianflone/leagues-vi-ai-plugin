package com.leaguesai.agent;

import lombok.Builder;
import lombok.Data;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
public class PlayerContext {
    private final Map<Skill, Integer> levels;
    private final Map<Skill, Integer> xp;
    private final Map<String, Integer> inventory;
    private final Map<String, Integer> equipment;
    private final Set<String> completedTasks;
    private final Set<String> unlockedAreas;
    private final WorldPoint location;
    private final int leaguePoints;
    private final int combatLevel;
    private final String currentGoal;
    private final List<PlannedStep> currentPlan;
}
