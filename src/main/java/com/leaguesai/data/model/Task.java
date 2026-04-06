package com.leaguesai.data.model;

import lombok.Builder;
import lombok.Data;
import net.runelite.api.coords.WorldPoint;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class Task {
    private String id;
    private String name;
    private String description;
    private String wikiUrl;
    private Difficulty difficulty;
    private int points;
    private String area;
    private String category;
    private Map<String, Integer> skillsRequired;
    private List<String> questsRequired;
    private List<String> tasksRequired;
    private Map<String, Integer> itemsRequired;
    private WorldPoint location;
    private List<NpcTarget> targetNpcs;
    private List<ObjectTarget> targetObjects;
    private List<ItemTarget> targetItems;

    @Data
    @Builder
    public static class NpcTarget {
        private int id;
        private String name;
    }

    @Data
    @Builder
    public static class ObjectTarget {
        private int id;
        private String name;
    }

    @Data
    @Builder
    public static class ItemTarget {
        private int id;
        private String name;
    }
}
