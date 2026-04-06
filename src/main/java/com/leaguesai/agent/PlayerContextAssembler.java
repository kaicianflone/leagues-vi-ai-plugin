package com.leaguesai.agent;

import com.leaguesai.core.monitors.LocationMonitor;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Singleton
public class PlayerContextAssembler {

    private final Client client;
    private final LocationMonitor locationMonitor;

    // Mutable internal state — writes are synchronized, volatile reads for simple scalars
    private final Set<String> completedTasks = new HashSet<>();
    private final Set<String> unlockedAreas = new HashSet<>();
    private volatile String currentGoal = "";
    private volatile List<PlannedStep> currentPlan = new ArrayList<>();

    @Inject
    public PlayerContextAssembler(Client client, LocationMonitor locationMonitor) {
        this.client = client;
        this.locationMonitor = locationMonitor;
    }

    public PlayerContext assemble() {
        return PlayerContext.builder()
                .levels(getSkillLevels())
                .xp(getSkillXp())
                .inventory(getInventory())
                .equipment(getEquipment())
                .completedTasks(new HashSet<>(completedTasks))
                .unlockedAreas(new HashSet<>(unlockedAreas))
                .location(locationMonitor.getCurrentLocation())
                .leaguePoints(0) // TODO: requires unknown varbit
                .combatLevel(client.getLocalPlayer() != null ? client.getLocalPlayer().getCombatLevel() : 0)
                .currentGoal(currentGoal)
                .currentPlan(new ArrayList<>(currentPlan))
                .build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Map<Skill, Integer> getSkillLevels() {
        Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
        for (Skill skill : Skill.values()) {
            if (skill == Skill.OVERALL) {
                continue;
            }
            levels.put(skill, client.getRealSkillLevel(skill));
        }
        return levels;
    }

    private Map<Skill, Integer> getSkillXp() {
        Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
        for (Skill skill : Skill.values()) {
            if (skill == Skill.OVERALL) {
                continue;
            }
            xp.put(skill, client.getSkillExperience(skill));
        }
        return xp;
    }

    private Map<Integer, Integer> getInventory() {
        return buildItemMap(InventoryID.INVENTORY);
    }

    private Map<Integer, Integer> getEquipment() {
        return buildItemMap(InventoryID.EQUIPMENT);
    }

    private Map<Integer, Integer> buildItemMap(InventoryID inventoryID) {
        Map<Integer, Integer> map = new HashMap<>();
        ItemContainer container = client.getItemContainer(inventoryID);
        if (container == null) {
            return map;
        }
        for (Item item : container.getItems()) {
            if (item == null || item.getId() == -1) {
                continue;
            }
            map.merge(item.getId(), item.getQuantity(), Integer::sum);
        }
        return map;
    }

    // -------------------------------------------------------------------------
    // Mutators
    // -------------------------------------------------------------------------

    public synchronized void markTaskCompleted(String taskId) {
        completedTasks.add(taskId);
    }

    public synchronized void markAreaUnlocked(String area) {
        unlockedAreas.add(area);
    }

    public void setCurrentGoal(String goal) {
        this.currentGoal = goal;
    }

    public void setCurrentPlan(List<PlannedStep> plan) {
        this.currentPlan = plan;
    }
}
