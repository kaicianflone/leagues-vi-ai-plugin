package com.leaguesai.agent;

import com.leaguesai.data.model.ItemDependency;
import com.leaguesai.overlay.OverlayData;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds skill-training {@link PlannedStep}s for items obtained by levelling a
 * skill (smithing, crafting, fletching, herblore, cooking, etc.).
 *
 * <p>Given an item dependency chain, this class:
 * <ol>
 *   <li>Identifies which skill and level are required per step.</li>
 *   <li>Computes the XP gap between the player's current XP and the required level.</li>
 *   <li>Looks up the nearest training location from a static table.</li>
 *   <li>Returns ordered {@link PlannedStep}s with overlay coordinates so the
 *       player can walk to the location and start training.</li>
 * </ol>
 *
 * <p>XP thresholds use the standard OSRS formula:
 * {@code xp(level) = floor(sum_{i=1}^{level-1} floor(i + 300 * 2^(i/7)) / 4)}.
 * Precomputed for levels 1–99 and stored in {@link #LEVEL_XP}.
 */
@Slf4j
public class SkillingStepBuilder {

    // -------------------------------------------------------------------------
    // OSRS XP table (levels 1–99, index = level)
    // Precomputed using the standard formula. Level 0 is unused (index padding).
    // -------------------------------------------------------------------------
    static final int[] LEVEL_XP = computeLevelXpTable();

    private static int[] computeLevelXpTable() {
        int[] xp = new int[100]; // indices 0-99
        xp[0] = 0;
        xp[1] = 0;
        int total = 0;
        for (int i = 1; i < 99; i++) {
            total += (int) Math.floor(i + 300.0 * Math.pow(2.0, i / 7.0));
            xp[i + 1] = (int) Math.floor(total / 4.0);
        }
        return xp;
    }

    /**
     * Returns the XP required to reach {@code level} from level 1.
     * Clamps to valid range [1, 99].
     */
    public static int xpForLevel(int level) {
        if (level <= 1) return 0;
        if (level > 99) level = 99;
        return LEVEL_XP[level];
    }

    /**
     * Returns the level a player is at given their XP.
     */
    public static int levelForXp(int xp) {
        for (int lvl = 99; lvl >= 1; lvl--) {
            if (xp >= LEVEL_XP[lvl]) return lvl;
        }
        return 1;
    }

    // -------------------------------------------------------------------------
    // Training location lookup: skill → best F2P/early training spot WorldPoint
    // These are approximate tile coordinates of common training locations.
    // -------------------------------------------------------------------------

    private static final Map<String, WorldPoint> TRAINING_LOCATIONS = new HashMap<>();
    private static final Map<String, String> TRAINING_LOCATION_NAMES = new HashMap<>();

    static {
        // Smithing
        TRAINING_LOCATIONS.put("SMITHING", new WorldPoint(3186, 3418, 0)); // Varrock west bank + anvil
        TRAINING_LOCATION_NAMES.put("SMITHING", "Varrock west bank & anvil");

        // Crafting
        TRAINING_LOCATIONS.put("CRAFTING", new WorldPoint(3093, 3244, 0)); // Crafting guild (members)
        TRAINING_LOCATION_NAMES.put("CRAFTING", "Al-Kharid furnace");

        // Fletching
        TRAINING_LOCATIONS.put("FLETCHING", new WorldPoint(3211, 3247, 0)); // Anywhere (no location needed)
        TRAINING_LOCATION_NAMES.put("FLETCHING", "Draynor Village (bank nearby)");

        // Herblore
        TRAINING_LOCATIONS.put("HERBLORE", new WorldPoint(3211, 3247, 0));
        TRAINING_LOCATION_NAMES.put("HERBLORE", "any bank");

        // Cooking
        TRAINING_LOCATIONS.put("COOKING", new WorldPoint(3271, 3180, 0)); // Al-Kharid range
        TRAINING_LOCATION_NAMES.put("COOKING", "Al-Kharid range");

        // Magic
        TRAINING_LOCATIONS.put("MAGIC", new WorldPoint(3213, 3424, 0));
        TRAINING_LOCATION_NAMES.put("MAGIC", "Varrock (near GE)");

        // Firemaking
        TRAINING_LOCATIONS.put("FIREMAKING", new WorldPoint(3211, 3430, 0));
        TRAINING_LOCATION_NAMES.put("FIREMAKING", "Grand Exchange / Varrock");

        // Mining
        TRAINING_LOCATIONS.put("MINING", new WorldPoint(3176, 3367, 0)); // Varrock east mine
        TRAINING_LOCATION_NAMES.put("MINING", "Varrock east mine");

        // Woodcutting
        TRAINING_LOCATIONS.put("WOODCUTTING", new WorldPoint(3266, 3479, 0)); // Varrock GE trees
        TRAINING_LOCATION_NAMES.put("WOODCUTTING", "Varrock GE trees");

        // Fishing
        TRAINING_LOCATIONS.put("FISHING", new WorldPoint(3238, 3170, 0)); // Al-Kharid
        TRAINING_LOCATION_NAMES.put("FISHING", "Lumbridge swamp / Al-Kharid");

        // Runecrafting
        TRAINING_LOCATIONS.put("RUNECRAFTING", new WorldPoint(3130, 3403, 0)); // Varrock rune essence mine
        TRAINING_LOCATION_NAMES.put("RUNECRAFTING", "Varrock → rune essence mine");

        // Agility
        TRAINING_LOCATIONS.put("AGILITY", new WorldPoint(2473, 3436, 0)); // Gnome Stronghold course
        TRAINING_LOCATION_NAMES.put("AGILITY", "Gnome Stronghold agility course");

        // Thieving
        TRAINING_LOCATIONS.put("THIEVING", new WorldPoint(3213, 3247, 0));
        TRAINING_LOCATION_NAMES.put("THIEVING", "Lumbridge guards / market stalls");

        // Hunter
        TRAINING_LOCATIONS.put("HUNTER", new WorldPoint(2530, 2918, 0)); // Feldip Hills
        TRAINING_LOCATION_NAMES.put("HUNTER", "Feldip Hills");

        // Construction
        TRAINING_LOCATIONS.put("CONSTRUCTION", new WorldPoint(3087, 3506, 0)); // Rimmington (POH portal)
        TRAINING_LOCATION_NAMES.put("CONSTRUCTION", "Rimmington POH portal");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Build skilling steps for an ordered list of item dependencies that require
     * skill training (SMITHED, CRAFTED, FLETCHED, etc.).
     *
     * <p>For each dep requiring a skill:
     * <ul>
     *   <li>Computes how much XP the player needs to gain to reach the required level.</li>
     *   <li>Picks the nearest known training location for that skill.</li>
     *   <li>Emits a {@link PlannedStep} with the location and a human-readable instruction.</li>
     * </ul>
     *
     * @param deps       ordered dependency list from {@link ItemDependencyGraph#expand}
     * @param playerXp   the player's current XP map (Skill → XP); null = assume level 1
     * @return ordered list of skilling steps, empty when no skill requirements are present
     */
    public static List<PlannedStep> buildSkillingSteps(
            List<ItemDependency> deps, Map<Skill, Integer> playerXp) {

        if (deps == null || deps.isEmpty()) return Collections.emptyList();

        List<PlannedStep> steps = new ArrayList<>();

        for (ItemDependency dep : deps) {
            if (!dep.getObtainMethod().isRecursable()) continue; // terminal methods handled elsewhere
            String skillName = dep.getSkillRequired();
            if (skillName == null || skillName.isBlank()) continue;

            int requiredLevel = dep.getSkillLevel();
            if (requiredLevel <= 1) continue;

            // Resolve current player level for this skill
            int currentXp = 0;
            int currentLevel = 1;
            if (playerXp != null) {
                try {
                    Skill skill = Skill.valueOf(skillName.toUpperCase(Locale.ROOT));
                    Integer xp = playerXp.get(skill);
                    if (xp != null) {
                        currentXp = xp;
                        currentLevel = levelForXp(xp);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Unknown skill name — use level 1 as baseline
                }
            }

            if (currentLevel >= requiredLevel) continue; // already have the level, no step needed

            int neededXp = xpForLevel(requiredLevel) - currentXp;
            String skillKey = skillName.toUpperCase(Locale.ROOT);
            WorldPoint location = TRAINING_LOCATIONS.get(skillKey);
            String locationName = TRAINING_LOCATION_NAMES.getOrDefault(skillKey, skillName + " training spot");

            String instruction = buildSkillingInstruction(dep, requiredLevel, currentLevel, neededXp, locationName);

            OverlayData overlayData = OverlayData.builder()
                    .targetTile(location)
                    .targetNpcIds(Collections.emptyList())
                    .targetNpcNames(Collections.emptyList())
                    .targetObjectIds(Collections.emptyList())
                    .targetItemIds(Collections.emptyList())
                    .pathPoints(Collections.emptyList())
                    .widgetIds(Collections.emptyList())
                    .shopItemGameIds(Collections.emptyList())
                    .showArrow(location != null)
                    .showMinimap(location != null)
                    .showWorldMap(location != null)
                    .build();

            steps.add(PlannedStep.builder()
                    .task(null)
                    .destination(location)
                    .instruction(instruction)
                    .overlayData(overlayData)
                    .build());

            log.info("SkillingStepBuilder: {} lv{} → lv{} ({} xp needed) at {}",
                    skillName, currentLevel, requiredLevel, String.format("%,d", neededXp), locationName);
        }

        return steps;
    }

    private static String buildSkillingInstruction(
            ItemDependency dep, int requiredLevel, int currentLevel, int neededXp, String locationName) {
        StringBuilder sb = new StringBuilder();
        String method = toTitleCase(dep.getObtainMethod().name());
        sb.append(method).append(" ").append(dep.getItemName());

        if (dep.getSkillRequired() != null) {
            sb.append(" (").append(dep.getSkillRequired()).append(" lv ")
              .append(requiredLevel).append(")");
        }

        sb.append(" — train from lv ").append(currentLevel)
          .append(" to lv ").append(requiredLevel);

        if (neededXp > 0) {
            sb.append(" (").append(String.format("%,d", neededXp)).append(" xp)");
        }

        sb.append(" at ").append(locationName);
        return sb.toString();
    }

    private static String toTitleCase(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
