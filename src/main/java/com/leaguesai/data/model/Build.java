package com.leaguesai.data.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
public class Build {
    private String id;                              // slug: "melee_bosser_v1"
    private String name;                            // "Melee Bosser"
    private String description;
    private String author;                          // "seed" or "@kai"
    private int version;
    private Map<GearSlot, String> gear;             // slot → GearItem id (null = no item in slot)
    private Set<String> relicIds;
    private Set<String> areaIds;
    private Set<String> pactIds;                    // must fit 40-slot budget
    private Map<String, Integer> targetSkills;      // optional {"attack": 90}
    private List<String> notes;                     // free-form tips
}
