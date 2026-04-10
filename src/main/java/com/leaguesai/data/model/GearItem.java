package com.leaguesai.data.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class GearItem {
    private String id;           // stable slug: "bandos_chestplate"
    private int wikiItemId;      // OSRS item ID for equipment monitor
    private String name;         // display: "Bandos chestplate"
    private GearSlot slot;
    private String region;       // nullable — null = global drop

    // 14 combat stats (all camelCase to match gear.json)
    private int attackStab;
    private int attackSlash;
    private int attackCrush;
    private int attackMagic;
    private int attackRanged;
    private int defenceStab;
    private int defenceSlash;
    private int defenceCrush;
    private int defenceMagic;
    private int defenceRanged;
    private int meleeStrength;
    private int magicDamage;
    private int rangedStrength;
    private int prayerBonus;

    private double weight;
    private Map<String, Integer> skillRequirements;   // {"defence": 65} — nullable
    private String wikiUrl;
    private List<String> taskOverrides;               // task IDs that grant this item
}
