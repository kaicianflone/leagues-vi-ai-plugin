package com.leaguesai.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class AnimationResolver {

    private static final Set<Integer> RANGED_WEAPONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    839,   // shortbow
                    841,   // longbow
                    4212,  // magic shortbow
                    11235  // dark bow
            ))
    );

    private static final Set<Integer> MAGIC_WEAPONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    1381,  // staff of fire
                    1383,  // staff of water
                    4675,  // ancient staff
                    6562   // master wand
            ))
    );

    private AnimationResolver() {
        // static utility
    }

    public static AnimationType resolve(String taskCategory, String taskSkill,
                                        Set<Integer> equippedItemIds, boolean inTransit) {
        if (taskCategory == null) {
            return AnimationType.IDLE;
        }

        if (inTransit) {
            return AnimationType.WALKING;
        }

        if ("combat".equalsIgnoreCase(taskCategory)) {
            if (equippedItemIds != null) {
                for (int id : equippedItemIds) {
                    if (RANGED_WEAPONS.contains(id)) {
                        return AnimationType.RANGED;
                    }
                }
                for (int id : equippedItemIds) {
                    if (MAGIC_WEAPONS.contains(id)) {
                        return AnimationType.MAGE;
                    }
                }
            }
            return AnimationType.MELEE;
        }

        if (taskSkill != null) {
            switch (taskSkill.toLowerCase()) {
                case "cooking":    return AnimationType.COOKING;
                case "fishing":    return AnimationType.FISHING;
                case "woodcutting": return AnimationType.WOODCUTTING;
                case "mining":     return AnimationType.MINING;
                default:           break;
            }
        }

        if ("banking".equalsIgnoreCase(taskCategory)) {
            return AnimationType.BANKING;
        }

        return AnimationType.IDLE;
    }
}
