package com.leaguesai.agent;

import net.runelite.api.coords.WorldPoint;

import java.util.HashMap;
import java.util.Map;

/**
 * Representative WorldPoints for each Leagues VI area.
 * Used as fallback destinations when a task has no scraped precise location,
 * so the minimap/world-map overlay can still point the player in the right direction.
 *
 * Coordinates are the main teleport hub / central landmark for each region.
 * Verify against the in-game Demonic Pacts League on launch day if points feel off.
 */
public final class AreaHubs {

    private static final Map<String, WorldPoint> HUBS = new HashMap<>();

    static {
        HUBS.put("varlamore",  new WorldPoint(1842, 3043, 0)); // Civitas illa Fortis center
        HUBS.put("kourend",    new WorldPoint(1558, 3530, 0)); // Great Kourend castle
        HUBS.put("desert",     new WorldPoint(3293, 3163, 0)); // Al Kharid / Shanty Pass
        HUBS.put("fremennik",  new WorldPoint(2654, 3634, 0)); // Rellekka
        HUBS.put("kandarin",   new WorldPoint(2660, 3305, 0)); // East Ardougne
        HUBS.put("asgarnia",   new WorldPoint(2964, 3381, 0)); // Falador
        HUBS.put("morytania",  new WorldPoint(3494, 3487, 0)); // Canifis
        HUBS.put("wilderness", new WorldPoint(3087, 3502, 0)); // Edgeville
        HUBS.put("tirannwn",   new WorldPoint(2208, 3328, 0)); // Prifddinas
        HUBS.put("karamja",    new WorldPoint(2798, 3212, 0)); // Brimhaven / Shilo Village
    }

    private AreaHubs() {}

    /**
     * Returns the hub WorldPoint for the given area id (case-insensitive).
     * Returns null for unknown/null areas (e.g. "general" cross-area tasks).
     */
    public static WorldPoint get(String areaId) {
        if (areaId == null) return null;
        return HUBS.get(areaId.toLowerCase());
    }

    /**
     * Returns the representative location for a task: the scraped precise location
     * if available, otherwise the area hub, otherwise null.
     */
    public static WorldPoint resolve(WorldPoint precise, String areaId) {
        if (precise != null) return precise;
        return get(areaId);
    }
}
