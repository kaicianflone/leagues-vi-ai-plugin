package com.leaguesai.agent;

import net.runelite.api.coords.WorldPoint;

import java.util.*;

/**
 * Static map of known OSRS area/region slugs to major teleport
 * destination WorldPoints.
 *
 * <p>Keys are lowercase area slugs as produced by the scraper (e.g.
 * {@code "misthalin"}, {@code "kandarin"}, {@code "desert"}). Values are
 * one or more representative WorldPoints that a player can fast-travel to
 * after unlocking that area.
 *
 * <p>Used by {@link ProximityOptimizer} to treat teleport destinations as
 * cost-zero waypoints when computing travel-time estimates between tasks.
 * Pre-launch data — extend once Leagues VI publishes the exact relic/area
 * teleport mechanics on the wiki.
 */
public final class RelicTeleportData {

    private static final Map<String, List<WorldPoint>> DESTINATIONS = new HashMap<>();

    static {
        // Misthalin
        reg("misthalin", wp(3222, 3218), wp(3210, 3424));   // Lumbridge, Varrock centre
        reg("lumbridge", wp(3222, 3218));
        reg("varrock",   wp(3210, 3424));
        reg("edgeville", wp(3093, 3502));
        reg("draynor",   wp(3081, 3250));

        // Asgarnia
        reg("asgarnia",  wp(2964, 3380), wp(2894, 3443));   // Falador, Taverley
        reg("falador",   wp(2964, 3380));
        reg("taverly",   wp(2894, 3443));
        reg("taverley",  wp(2894, 3443));
        reg("burthorpe", wp(2898, 3545));

        // Kandarin
        reg("kandarin",  wp(2757, 3478), wp(2662, 3306), wp(2727, 3491));  // Camelot, Ardougne, Seers'
        reg("camelot",   wp(2757, 3478));
        reg("ardougne",  wp(2662, 3306));
        reg("seers",     wp(2727, 3491));
        reg("yanille",   wp(2607, 3092));
        reg("catherby",  wp(2796, 3442));
        reg("fishing_guild", wp(2612, 3393));

        // Morytania
        reg("morytania", wp(3492, 3487), wp(3612, 3516));   // Canifis, Burgh de Rott
        reg("canifis",   wp(3492, 3487));
        reg("kharyrll",  wp(3492, 3487));

        // Fremennik
        reg("fremennik", wp(2664, 3652), wp(2549, 3759));   // Rellekka, Waterbirth
        reg("rellekka",  wp(2664, 3652));
        reg("waterbirth", wp(2549, 3759));
        reg("neitiznot", wp(2338, 3807));
        reg("jatizso",   wp(2409, 3796));

        // Tirannwn / Elf lands
        reg("tirannwn",  wp(2208, 3257));
        reg("lletya",    wp(2354, 3167));
        reg("prifddinas", wp(3226, 6102));

        // Gnome / Tree Gnome Stronghold
        reg("gnome",     wp(2461, 3445));
        reg("tree_gnome_stronghold", wp(2461, 3445));

        // Desert
        reg("desert",    wp(3352, 2952), wp(3429, 2892));   // Pollnivneach, Nardah
        reg("pollnivneach", wp(3352, 2952));
        reg("nardah",    wp(3429, 2892));
        reg("sophanem",  wp(3317, 2751));
        reg("menaphos",  wp(3225, 2815));

        // Karamja
        reg("karamja",   wp(2757, 3179), wp(2851, 2954));   // Brimhaven, Shilo Village
        reg("brimhaven", wp(2757, 3179));
        reg("shilo_village", wp(2851, 2954));
        reg("tzhaar",    wp(2477, 5176));

        // Wilderness
        reg("wilderness", wp(3093, 3502), wp(3222, 3374));  // Edgeville, Wildy crater
        reg("edgeville_dungeon", wp(3133, 9897));

        // Zeah / Great Kourend
        reg("zeah",      wp(1639, 3672), wp(1726, 3714));   // Kourend castle, Hosidius
        reg("kourend",   wp(1639, 3672));
        reg("hosidius",  wp(1726, 3714));
        reg("shayzien",  wp(1495, 3632));
        reg("arceuus",   wp(1712, 3886));
        reg("lovakengj", wp(1526, 3773));
        reg("piscarilius", wp(1803, 3786));

        // Kebos / Lizardman
        reg("kebos",     wp(1440, 3844));
        reg("mount_karuulm", wp(1440, 3844));

        // Pest Control / Castle Wars
        reg("pest_control", wp(2657, 2659));
        reg("castle_wars",  wp(2442, 3092));

        // God Wars Dungeon
        reg("god_wars_dungeon", wp(2908, 3739));

        // Fossil Island
        reg("fossil_island", wp(3727, 3806));

        // Myths' Guild / Corsair Cove
        reg("myths_guild",  wp(2462, 2854));
        reg("corsair_cove", wp(2570, 2864));
    }

    private RelicTeleportData() {}

    /**
     * Returns teleport destination WorldPoints for the given area or relic id.
     * Case-insensitive. Returns an empty list (never null) when id is unknown.
     */
    public static List<WorldPoint> getDestinations(String areaOrRelicId) {
        if (areaOrRelicId == null || areaOrRelicId.isEmpty()) {
            return Collections.emptyList();
        }
        List<WorldPoint> found = DESTINATIONS.get(areaOrRelicId.toLowerCase(Locale.ROOT));
        return found != null ? Collections.unmodifiableList(found) : Collections.emptyList();
    }

    private static WorldPoint wp(int x, int y) {
        return new WorldPoint(x, y, 0);
    }

    private static void reg(String key, WorldPoint... pts) {
        DESTINATIONS.put(key, Arrays.asList(pts));
    }
}
