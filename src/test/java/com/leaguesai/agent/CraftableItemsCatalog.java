package com.leaguesai.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reference catalog of craftable items used as test fixtures across
 * {@link WikiItemLookupTest}, {@link GoalSpecParserCraftTest}, and
 * {@link GoalPlannerCraftTest}.
 *
 * <p>Each entry captures the canonical name, wiki slug, skill, level,
 * materials (name + qty), and expected xp so tests can assert against
 * known-good values parsed from the OSRS wiki.
 */
public final class CraftableItemsCatalog {

    private CraftableItemsCatalog() {}

    /** Immutable descriptor for one craftable item. Plain Java — no Lombok in test sources. */
    public static final class ItemEntry {
        private final String displayName;
        private final String wikiSlug;
        private final String skill;
        private final int levelRequired;
        private final double xpGained;
        private final List<String> materialNames;
        private final List<Integer> materialQtys;
        private final List<String> triggerPhrases;

        public ItemEntry(String displayName, String wikiSlug, String skill,
                         int levelRequired, double xpGained,
                         List<String> materialNames, List<Integer> materialQtys,
                         List<String> triggerPhrases) {
            this.displayName   = displayName;
            this.wikiSlug      = wikiSlug;
            this.skill         = skill;
            this.levelRequired = levelRequired;
            this.xpGained      = xpGained;
            this.materialNames = materialNames;
            this.materialQtys  = materialQtys;
            this.triggerPhrases = triggerPhrases;
        }

        /** Display name exactly as it appears on the OSRS wiki page. */
        public String getDisplayName()    { return displayName; }
        /** Wiki page slug used in the API URL, e.g. "Air_battlestaff". */
        public String getWikiSlug()       { return wikiSlug; }
        /** Skill name, e.g. "Crafting". */
        public String getSkill()          { return skill; }
        /** Skill level required. */
        public int getLevelRequired()     { return levelRequired; }
        /** XP gained per action. */
        public double getXpGained()       { return xpGained; }
        /** Ordered list of material names (must match wiki mat1, mat2, ... order). */
        public List<String> getMaterialNames()  { return materialNames; }
        /** Quantities corresponding to materialNames. */
        public List<Integer> getMaterialQtys()  { return materialQtys; }
        /**
         * Natural-language alias phrases a player might type to trigger the
         * crafting plan (lowercased).
         */
        public List<String> getTriggerPhrases() { return triggerPhrases; }
    }

    // -------------------------------------------------------------------------
    // Crafting
    // -------------------------------------------------------------------------

    public static final ItemEntry AIR_BATTLESTAFF = new ItemEntry(
            "Air battlestaff",
            "Air_battlestaff",
            "Crafting",
            66, 137.5,
            Arrays.asList("Battlestaff", "Air orb"),
            Arrays.asList(1, 1),
            Arrays.asList(
                    "make a staff of air",
                    "now i want to make a staff of air",
                    "make an air staff",
                    "craft an air battlestaff",
                    "i want to make a staff of air"
            )
    );

    public static final ItemEntry WATER_BATTLESTAFF = new ItemEntry(
            "Water battlestaff",
            "Water_battlestaff",
            "Crafting",
            54, 100.0,
            Arrays.asList("Battlestaff", "Water orb"),
            Arrays.asList(1, 1),
            Arrays.asList("make a staff of water", "make water staff", "craft water battlestaff")
    );

    public static final ItemEntry EARTH_BATTLESTAFF = new ItemEntry(
            "Earth battlestaff",
            "Earth_battlestaff",
            "Crafting",
            58, 112.5,
            Arrays.asList("Battlestaff", "Earth orb"),
            Arrays.asList(1, 1),
            Arrays.asList("make a staff of earth", "craft earth battlestaff")
    );

    public static final ItemEntry FIRE_BATTLESTAFF = new ItemEntry(
            "Fire battlestaff",
            "Fire_battlestaff",
            "Crafting",
            62, 125.0,
            Arrays.asList("Battlestaff", "Fire orb"),
            Arrays.asList(1, 1),
            Arrays.asList("make a staff of fire", "craft fire battlestaff")
    );

    public static final ItemEntry BLACK_DHIDE_BODY = new ItemEntry(
            "Black d'hide body",
            "Black_d%27hide_body",
            "Crafting",
            84, 258.0,
            Arrays.asList("Black dragon leather"),
            Collections.singletonList(3),
            Arrays.asList("make black dragonhide body", "craft black dhide body")
    );

    public static final ItemEntry DIAMOND_AMULET = new ItemEntry(
            "Diamond amulet",
            "Diamond_amulet",
            "Crafting",
            70, 100.0,
            Arrays.asList("Diamond", "Gold bar"),
            Arrays.asList(1, 1),
            Arrays.asList("craft diamond amulet", "make diamond amulet")
    );

    public static final ItemEntry EMERALD_RING = new ItemEntry(
            "Emerald ring",
            "Emerald_ring",
            "Crafting",
            27, 55.0,
            Arrays.asList("Emerald", "Gold bar"),
            Arrays.asList(1, 1),
            Arrays.asList("make emerald ring", "craft emerald ring")
    );

    // -------------------------------------------------------------------------
    // Smithing
    // -------------------------------------------------------------------------

    public static final ItemEntry IRON_PLATEBODY = new ItemEntry(
            "Iron platebody",
            "Iron_platebody",
            "Smithing",
            33, 125.0,
            Collections.singletonList("Iron bar"),
            Collections.singletonList(5),
            Arrays.asList("smith iron platebody", "make iron platebody")
    );

    public static final ItemEntry STEEL_PLATEBODY = new ItemEntry(
            "Steel platebody",
            "Steel_platebody",
            "Smithing",
            48, 187.5,
            Collections.singletonList("Steel bar"),
            Collections.singletonList(5),
            Arrays.asList("smith steel platebody", "make steel platebody")
    );

    public static final ItemEntry MITHRIL_PLATELEGS = new ItemEntry(
            "Mithril platelegs",
            "Mithril_platelegs",
            "Smithing",
            66, 120.0,
            Collections.singletonList("Mithril bar"),
            Collections.singletonList(4),
            Arrays.asList("smith mithril platelegs", "make mithril platelegs")
    );

    public static final ItemEntry RUNE_PLATEBODY = new ItemEntry(
            "Rune platebody",
            "Rune_platebody",
            "Smithing",
            99, 375.0,
            Collections.singletonList("Rune bar"),
            Collections.singletonList(5),
            Arrays.asList("smith rune platebody", "make rune platebody", "craft rune platebody")
    );

    // -------------------------------------------------------------------------
    // Fletching
    // -------------------------------------------------------------------------

    public static final ItemEntry MAGIC_LONGBOW = new ItemEntry(
            "Magic longbow",
            "Magic_longbow",
            "Fletching",
            85, 91.5,
            Arrays.asList("Magic logs", "Bowstring"),
            Arrays.asList(1, 1),
            Arrays.asList("fletch magic longbow", "make magic longbow")
    );

    public static final ItemEntry YEW_LONGBOW = new ItemEntry(
            "Yew longbow",
            "Yew_longbow",
            "Fletching",
            70, 75.0,
            Arrays.asList("Yew logs", "Bowstring"),
            Arrays.asList(1, 1),
            Arrays.asList("fletch yew longbow", "make yew longbow")
    );

    public static final ItemEntry MAPLE_LONGBOW = new ItemEntry(
            "Maple longbow",
            "Maple_longbow",
            "Fletching",
            55, 58.3,
            Arrays.asList("Maple logs", "Bowstring"),
            Arrays.asList(1, 1),
            Arrays.asList("fletch maple longbow", "make maple longbow")
    );

    // -------------------------------------------------------------------------
    // Cooking
    // -------------------------------------------------------------------------

    public static final ItemEntry SHARK = new ItemEntry(
            "Shark",
            "Shark",
            "Cooking",
            80, 210.0,
            Collections.singletonList("Raw shark"),
            Collections.singletonList(1),
            Arrays.asList("cook shark", "make a shark")
    );

    public static final ItemEntry SWORDFISH = new ItemEntry(
            "Swordfish",
            "Swordfish",
            "Cooking",
            45, 140.0,
            Collections.singletonList("Raw swordfish"),
            Collections.singletonList(1),
            Arrays.asList("cook swordfish", "make swordfish")
    );

    public static final ItemEntry LOBSTER = new ItemEntry(
            "Lobster",
            "Lobster",
            "Cooking",
            40, 120.0,
            Collections.singletonList("Raw lobster"),
            Collections.singletonList(1),
            Arrays.asList("cook lobster", "cook a lobster")
    );

    // -------------------------------------------------------------------------
    // Herblore
    // -------------------------------------------------------------------------

    public static final ItemEntry PRAYER_POTION = new ItemEntry(
            "Prayer potion",
            "Prayer_potion",
            "Herblore",
            38, 87.5,
            Arrays.asList("Ranarr weed", "Snape grass"),
            Arrays.asList(1, 1),
            Arrays.asList("brew prayer potion", "make prayer potion", "craft prayer potion")
    );

    public static final ItemEntry SUPER_RESTORE = new ItemEntry(
            "Super restore",
            "Super_restore",
            "Herblore",
            63, 142.5,
            Arrays.asList("Snapdragon", "Red spiders' eggs"),
            Arrays.asList(1, 1),
            Arrays.asList("brew super restore", "make super restore")
    );

    // -------------------------------------------------------------------------
    // Convenience collections by skill
    // -------------------------------------------------------------------------

    public static final List<ItemEntry> CRAFTING = Arrays.asList(
            AIR_BATTLESTAFF, WATER_BATTLESTAFF, EARTH_BATTLESTAFF, FIRE_BATTLESTAFF,
            BLACK_DHIDE_BODY, DIAMOND_AMULET, EMERALD_RING
    );

    public static final List<ItemEntry> SMITHING = Arrays.asList(
            IRON_PLATEBODY, STEEL_PLATEBODY, MITHRIL_PLATELEGS, RUNE_PLATEBODY
    );

    public static final List<ItemEntry> FLETCHING = Arrays.asList(
            MAGIC_LONGBOW, YEW_LONGBOW, MAPLE_LONGBOW
    );

    public static final List<ItemEntry> COOKING = Arrays.asList(
            SHARK, SWORDFISH, LOBSTER
    );

    public static final List<ItemEntry> HERBLORE = Arrays.asList(
            PRAYER_POTION, SUPER_RESTORE
    );

    public static final List<ItemEntry> ALL = Arrays.asList(
            AIR_BATTLESTAFF, WATER_BATTLESTAFF, EARTH_BATTLESTAFF, FIRE_BATTLESTAFF,
            BLACK_DHIDE_BODY, DIAMOND_AMULET, EMERALD_RING,
            IRON_PLATEBODY, STEEL_PLATEBODY, MITHRIL_PLATELEGS, RUNE_PLATEBODY,
            MAGIC_LONGBOW, YEW_LONGBOW, MAPLE_LONGBOW,
            SHARK, SWORDFISH, LOBSTER,
            PRAYER_POTION, SUPER_RESTORE
    );
}
