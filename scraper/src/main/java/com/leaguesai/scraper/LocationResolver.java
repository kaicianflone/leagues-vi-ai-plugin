package com.leaguesai.scraper;

import java.util.HashMap;
import java.util.Map;

/**
 * Static lookup table mapping known OSRS location names to in-game tile
 * coordinates as {@code int[]{x, y, plane}}.
 */
public class LocationResolver {

    private static final Map<String, int[]> LOCATIONS = new HashMap<>();

    static {
        // Misthalin
        LOCATIONS.put("lumbridge",        new int[]{3222, 3218, 0});
        LOCATIONS.put("varrock",          new int[]{3210, 3424, 0});
        LOCATIONS.put("draynor village",  new int[]{3093, 3243, 0});
        LOCATIONS.put("edgeville",        new int[]{3087, 3490, 0});
        LOCATIONS.put("barbarian village",new int[]{3081, 3420, 0});

        // Asgarnia
        LOCATIONS.put("falador",          new int[]{2964, 3378, 0});
        LOCATIONS.put("port sarim",       new int[]{3011, 3206, 0});
        LOCATIONS.put("taverley",         new int[]{2894, 3428, 0});
        LOCATIONS.put("burthorpe",        new int[]{2899, 3544, 0});

        // Kandarin
        LOCATIONS.put("ardougne",         new int[]{2663, 3305, 0});
        LOCATIONS.put("catherby",         new int[]{2811, 3431, 0});
        LOCATIONS.put("seers village",    new int[]{2726, 3487, 0});

        // Karamja
        LOCATIONS.put("karamja",          new int[]{2954, 3147, 0});
        LOCATIONS.put("brimhaven",        new int[]{2757, 3179, 0});
        LOCATIONS.put("shilo village",    new int[]{2850, 2954, 0});

        // Morytania
        LOCATIONS.put("canifis",          new int[]{3487, 3486, 0});
        LOCATIONS.put("mort myre",        new int[]{3452, 3420, 0});
    }

    /**
     * Looks up coordinates for a location mentioned in a task description.
     * The check is case-insensitive and uses substring matching so that a
     * description containing "near Lumbridge" will still resolve.
     *
     * @param description task description or location string
     * @return {@code int[]{x, y, plane}} or {@code null} if no match
     */
    public static int[] resolve(String description) {
        if (description == null) {
            return null;
        }
        String lower = description.toLowerCase();
        for (Map.Entry<String, int[]> entry : LOCATIONS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
