package com.leaguesai.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-demand item obtain lookup for items that exist in the {@code items} catalog
 * but have no rows in {@code item_dependencies}.
 *
 * <p>When a player asks "I need a staff of air", the dependency graph is empty,
 * so we fall back here: fetch the OSRS wiki wikitext for the item's page, scan
 * for known shop/NPC names, and synthesise overlay-ready {@link ShopEntry} records.
 *
 * <p>Also attempts to extract the RuneLite item game-ID from the corresponding
 * Exchange page so the shop-widget overlay can highlight the correct item slot.
 *
 * <p>All HTTP calls are blocking. Call only from a background thread — never
 * from the client/EDT thread.
 */
@Slf4j
public class WikiItemLookup implements java.io.Closeable {

    private static final String WIKI_API =
            "https://oldschool.runescape.wiki/api.php?action=parse&page=%s&prop=wikitext&format=json";

    /** Contact header required by the OSRS wiki API policy. */
    private static final String USER_AGENT =
            "LeaguesVIAIPlugin/1.0 (OSRS RuneLite plugin; github.com/kaicianflone/leagues-vi-ai-plugin)";

    /**
     * Extracts the OSRS game item ID from the Infobox on the item's own wiki page.
     * The {{Infobox Item}} template uses {@code | id = N}.
     * Some items have multiple IDs: {@code | id = 1381, 1379} — we take the first.
     */
    private static final Pattern INFOBOX_ID_RE = Pattern.compile(
            "\\|\\s*id\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /** Extracts the shop sell price from the Infobox: {@code | value = N}. */
    private static final Pattern INFOBOX_VALUE_RE = Pattern.compile(
            "\\|\\s*value\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    // -------------------------------------------------------------------------
    // Static shop lookup tables (lowercase shop name → data)
    // -------------------------------------------------------------------------

    /** Lowercase shop name → approximate WorldPoint of the shop NPC. */
    private static final Map<String, WorldPoint> SHOP_LOCATIONS = new HashMap<>();

    /** Lowercase shop name → NPC display name (for NpcHighlightOverlay name matching). */
    private static final Map<String, String> SHOP_NPC_NAMES = new HashMap<>();

    /** NPC display name → OSRS NPC game ID (for ID-based NPC highlighting). */
    private static final Map<String, Integer> NPC_IDS = new HashMap<>();

    static {
        // Staffs
        SHOP_LOCATIONS.put("zaff's superior staffs", new WorldPoint(3203, 3434, 0));
        SHOP_NPC_NAMES.put("zaff's superior staffs", "Zaff");
        NPC_IDS.put("Zaff", 544);

        // Runes
        SHOP_LOCATIONS.put("aubury's rune shop", new WorldPoint(3253, 3401, 0));
        SHOP_NPC_NAMES.put("aubury's rune shop", "Aubury");
        NPC_IDS.put("Aubury", 553);

        // Port Sarim magic
        SHOP_LOCATIONS.put("betty's magic emporium", new WorldPoint(3013, 3258, 0));
        SHOP_NPC_NAMES.put("betty's magic emporium", "Betty");
        NPC_IDS.put("Betty", 522);

        // Archery
        SHOP_LOCATIONS.put("lowe's archery emporium", new WorldPoint(3232, 3422, 0));
        SHOP_NPC_NAMES.put("lowe's archery emporium", "Lowe");
        NPC_IDS.put("Lowe", 551);

        // Yanille magic guild
        SHOP_LOCATIONS.put("magic guild store (runes and staves)", new WorldPoint(2591, 3089, 0));
        SHOP_NPC_NAMES.put("magic guild store (runes and staves)", "Magic Guild Instructor");
        SHOP_LOCATIONS.put("magic guild store (magical weaponry)", new WorldPoint(2591, 3089, 0));
        SHOP_NPC_NAMES.put("magic guild store (magical weaponry)", "Magic Guild Instructor");
        NPC_IDS.put("Magic Guild Instructor", 2621);

        // Gnome stronghold
        SHOP_LOCATIONS.put("gulluck and sons", new WorldPoint(2559, 3306, 0));
        SHOP_NPC_NAMES.put("gulluck and sons", "Gulluck");
        NPC_IDS.put("Gulluck", 2066);

        // Pest control (void knight)
        SHOP_LOCATIONS.put("void knight magic store", new WorldPoint(2639, 2652, 0));
        SHOP_NPC_NAMES.put("void knight magic store", "Squire");

        SHOP_LOCATIONS.put("void knight archery store", new WorldPoint(2639, 2652, 0));
        SHOP_NPC_NAMES.put("void knight archery store", "Squire");

        SHOP_LOCATIONS.put("void knight melee store", new WorldPoint(2639, 2652, 0));
        SHOP_NPC_NAMES.put("void knight melee store", "Squire");

        // Al-Kharid
        SHOP_LOCATIONS.put("ranael's super skirt store", new WorldPoint(3300, 3175, 0));
        SHOP_NPC_NAMES.put("ranael's super skirt store", "Ranael");
        NPC_IDS.put("Ranael", 523);

        // Falador
        SHOP_LOCATIONS.put("cassie's shield shop", new WorldPoint(2975, 3383, 0));
        SHOP_NPC_NAMES.put("cassie's shield shop", "Cassie");
        NPC_IDS.put("Cassie", 542);

        // Slayer shops
        SHOP_LOCATIONS.put("slayer equipment", new WorldPoint(2432, 3539, 0));
        SHOP_NPC_NAMES.put("slayer equipment", "Turael");
        SHOP_LOCATIONS.put("slayer's staff shop", new WorldPoint(2432, 3539, 0));
        SHOP_NPC_NAMES.put("slayer's staff shop", "Turael");

        // Canifis general
        SHOP_LOCATIONS.put("canifis general store", new WorldPoint(3508, 3488, 0));
        SHOP_NPC_NAMES.put("canifis general store", "Barkers' Haberdashery");

        // Entrana
        SHOP_LOCATIONS.put("entrana", new WorldPoint(2852, 3348, 0));

        // Duel arena
        SHOP_LOCATIONS.put("mage of zamorak", new WorldPoint(3058, 3488, 0));
        SHOP_NPC_NAMES.put("mage of zamorak", "Mage of Zamorak");
        NPC_IDS.put("Mage of Zamorak", 172);

        // Shops with generic names
        SHOP_LOCATIONS.put("ardougne general store", new WorldPoint(2613, 3291, 0));
        SHOP_LOCATIONS.put("lumbridge general store", new WorldPoint(3211, 3247, 0));
        SHOP_LOCATIONS.put("draynor village marketplace", new WorldPoint(3079, 3247, 0));
    }

    // -------------------------------------------------------------------------
    // Crafting alias + obtain location tables
    // -------------------------------------------------------------------------

    /**
     * When the player says "make X", maps X (lowercase) to the wiki page slug
     * for the craftable form. E.g., "staff of air" → "Air_battlestaff".
     */
    private static final Map<String, String> CRAFT_ALIASES = new HashMap<>();

    static {
        CRAFT_ALIASES.put("staff of air",   "Air_battlestaff");
        CRAFT_ALIASES.put("air staff",      "Air_battlestaff");
        CRAFT_ALIASES.put("staff of water", "Water_battlestaff");
        CRAFT_ALIASES.put("water staff",    "Water_battlestaff");
        CRAFT_ALIASES.put("staff of fire",  "Fire_battlestaff");
        CRAFT_ALIASES.put("fire staff",     "Fire_battlestaff");
        CRAFT_ALIASES.put("staff of earth", "Earth_battlestaff");
        CRAFT_ALIASES.put("earth staff",    "Earth_battlestaff");
        CRAFT_ALIASES.put("battlestaff",    "Air_battlestaff"); // generic → most common
    }

    /**
     * Known WorldPoint locations for items that cannot be bought in shops
     * (obelisks, altars, etc.). Keyed by lowercase item name.
     */
    private static final Map<String, WorldPoint> OBTAIN_LOCATIONS = new HashMap<>();

    static {
        // Air obelisk — enter Edgeville dungeon, run north through Wilderness
        OBTAIN_LOCATIONS.put("air orb",        new WorldPoint(3097, 3468, 0)); // Edgeville dungeon entrance
        OBTAIN_LOCATIONS.put("water orb",       new WorldPoint(2875, 9821, 0)); // Taverley dungeon water obelisk
        OBTAIN_LOCATIONS.put("earth orb",       new WorldPoint(3109, 9974, 0)); // Edgeville dungeon earth obelisk
        OBTAIN_LOCATIONS.put("fire orb",        new WorldPoint(2834, 9814, 0)); // Taverley dungeon fire obelisk
        OBTAIN_LOCATIONS.put("cosmic rune",     new WorldPoint(2143, 4832, 0)); // Cosmic altar (via Zanaris)
        OBTAIN_LOCATIONS.put("unpowered orb",   new WorldPoint(2936, 3093, 0)); // Crafting guild
        OBTAIN_LOCATIONS.put("molten glass",    new WorldPoint(3271, 3186, 0)); // Al-Kharid furnace
        OBTAIN_LOCATIONS.put("iron bar",        new WorldPoint(3187, 3426, 0)); // Varrock west anvil
        OBTAIN_LOCATIONS.put("steel bar",       new WorldPoint(3187, 3426, 0));
        OBTAIN_LOCATIONS.put("mithril bar",     new WorldPoint(3187, 3426, 0));
        OBTAIN_LOCATIONS.put("adamantite bar",  new WorldPoint(3187, 3426, 0));
        OBTAIN_LOCATIONS.put("rune bar",        new WorldPoint(3187, 3426, 0));
    }

    /** Crafting location WorldPoint by lowercase skill name. Null = can be done anywhere. */
    private static final Map<String, WorldPoint> SKILL_CRAFT_LOCATIONS = new HashMap<>();
    private static final Map<String, String> SKILL_CRAFT_LOCATION_NAMES = new HashMap<>();

    static {
        SKILL_CRAFT_LOCATIONS.put("crafting", new WorldPoint(3212, 3213, 0)); // Lumbridge crafting table
        SKILL_CRAFT_LOCATION_NAMES.put("crafting", "your bank or crafting table");
        SKILL_CRAFT_LOCATIONS.put("smithing", new WorldPoint(3187, 3426, 0)); // Varrock west anvil
        SKILL_CRAFT_LOCATION_NAMES.put("smithing", "Varrock west anvil");
        SKILL_CRAFT_LOCATIONS.put("fletching", null); // inventory click, anywhere
        SKILL_CRAFT_LOCATION_NAMES.put("fletching", "anywhere (click item in inventory)");
        SKILL_CRAFT_LOCATIONS.put("cooking", new WorldPoint(3271, 3180, 0)); // Al-Kharid range
        SKILL_CRAFT_LOCATION_NAMES.put("cooking", "Al-Kharid range");
        SKILL_CRAFT_LOCATIONS.put("herblore", new WorldPoint(3096, 3354, 0)); // Draynor bank area
        SKILL_CRAFT_LOCATION_NAMES.put("herblore", "anywhere with herbs + vials");
        SKILL_CRAFT_LOCATIONS.put("firemaking", null);
        SKILL_CRAFT_LOCATION_NAMES.put("firemaking", "anywhere");
    }

    // -------------------------------------------------------------------------
    // Crafting recipe parsing patterns
    // -------------------------------------------------------------------------

    /**
     * Matches `|mat1 = Battlestaff` or `|mat1 = [[Gold bar|Gold bar]]` in wiki crafting templates.
     * Captures the full value up to end-of-line so wiki links containing `|` are preserved
     * intact for post-processing by {@link #WIKI_LINK_RE}.
     */
    private static final Pattern MAT_NAME_RE = Pattern.compile(
            "\\|\\s*mat(\\d+)\\s*=\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAT_QTY_RE = Pattern.compile(
            "\\|\\s*mat(\\d+)qty\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRAFT_LEVEL_RE = Pattern.compile(
            "\\|\\s*level\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRAFT_XP_RE = Pattern.compile(
            "\\|\\s*xp\\s*=\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE);
    /** Strips wiki link syntax `[[Page|Display]]` → `Display` (or `Page` if no alias). */
    private static final Pattern WIKI_LINK_RE = Pattern.compile(
            "\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]");

    /** Maps `{{TemplateName` → skill name. */
    private static final String[][] CRAFT_TEMPLATE_SKILLS = {
        {"{{Crafting",  "Crafting"},
        {"{{Smithing",  "Smithing"},
        {"{{Fletching", "Fletching"},
        {"{{Cooking",   "Cooking"},
        {"{{Herblore",  "Herblore"},
        {"{{Firemaking","Firemaking"},
        {"{{Construction","Construction"},
    };

    /** Nearest bank WorldPoint for each shop (lowercase shop name → bank tile). */
    private static final Map<String, WorldPoint> NEAREST_BANK = new HashMap<>();

    static {
        NEAREST_BANK.put("zaff's superior staffs",   new WorldPoint(3185, 3436, 0)); // Varrock west bank
        NEAREST_BANK.put("aubury's rune shop",        new WorldPoint(3254, 3420, 0)); // Varrock east bank
        NEAREST_BANK.put("betty's magic emporium",    new WorldPoint(3044, 3235, 0)); // Port Sarim deposit box
        NEAREST_BANK.put("lowe's archery emporium",   new WorldPoint(3254, 3420, 0)); // Varrock east bank
        NEAREST_BANK.put("magic guild store (runes and staves)", new WorldPoint(2613, 3093, 0)); // Yanille bank
        NEAREST_BANK.put("magic guild store (magical weaponry)", new WorldPoint(2613, 3093, 0));
        NEAREST_BANK.put("gulluck and sons",          new WorldPoint(2442, 3488, 0)); // Gnome bank
        NEAREST_BANK.put("void knight magic store",   new WorldPoint(2660, 2643, 0)); // Void knight bank
        NEAREST_BANK.put("void knight archery store", new WorldPoint(2660, 2643, 0));
        NEAREST_BANK.put("void knight melee store",   new WorldPoint(2660, 2643, 0));
        NEAREST_BANK.put("ranael's super skirt store",new WorldPoint(3269, 3167, 0)); // Al-Kharid bank
        NEAREST_BANK.put("cassie's shield shop",      new WorldPoint(2946, 3368, 0)); // Falador west bank
        NEAREST_BANK.put("ardougne general store",    new WorldPoint(2652, 3283, 0)); // East Ardougne bank
        NEAREST_BANK.put("lumbridge general store",   new WorldPoint(3208, 3220, 0)); // Lumbridge castle bank
    }

    // -------------------------------------------------------------------------
    // Instance
    // -------------------------------------------------------------------------

    private final OkHttpClient httpClient;
    private final Gson gson;

    public WikiItemLookup() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Represents one way to obtain an item (typically a shop or NPC trade).
     */
    @Value
    public static class ShopEntry {
        /** Human-readable shop/source name, e.g. "Zaff's Superior Staffs". */
        String shopName;

        /** NPC display name for overlay highlighting, e.g. "Zaff". May be null. */
        String npcName;

        /**
         * Approximate world tile of the shop NPC. Null when the shop is not in
         * the lookup table — overlays will skip tile/arrow/minimap in this case.
         */
        WorldPoint location;

        /** Nearest bank tile to this shop. Null when not in the lookup table. */
        WorldPoint nearestBank;

        /**
         * OSRS NPC game ID for {@link com.leaguesai.overlay.NpcHighlightOverlay}.
         * -1 when unknown — the overlay will fall back to name matching.
         */
        int npcGameId;

        /**
         * OSRS item game ID (RuneLite item ID) for shop-widget highlighting.
         * Parsed from the {@code | id = N} field in the item's Infobox. 0 = unknown.
         */
        int itemGameId;

        /**
         * Base value from the wiki Infobox ({@code | value = N}).
         * Shops typically sell at 130% of this value. 0 = unknown.
         */
        int shopValue;
    }

    /** One ingredient in a crafting recipe. */
    @Value
    public static class MaterialEntry {
        /** Display name from the wiki, e.g. "Battlestaff". */
        String itemName;
        /** How many are needed, e.g. 1. */
        int quantity;
    }

    /** A parsed crafting recipe from the OSRS wiki. */
    @Value
    public static class CraftingRecipe {
        /** Display name of the item being crafted, e.g. "Air battlestaff". */
        String craftedItem;
        /** Ordered list of required materials. */
        List<MaterialEntry> materials;
        /** Skill name, e.g. "Crafting". */
        String skill;
        /** Skill level required. */
        int levelRequired;
        /** XP gained per craft. */
        double xpGained;
        /**
         * WorldPoint of the crafting location (anvil, furnace, table, etc.).
         * Null when the action can be performed anywhere (fletching, herblore).
         */
        WorldPoint craftingLocation;
        /** Human-readable location name for the instruction string. */
        String craftingLocationName;
    }

    /**
     * Look up obtain sources for an item. Fetches the OSRS wiki wikitext, scans
     * for known shop names, and optionally fetches the Exchange page for the
     * item's game ID.
     *
     * @param pageSlug  wiki page slug, e.g. "Staff_of_air"
     * @param storedGameId the {@code wiki_item_id} already in the {@code items}
     *                  table (0 means unknown, triggers an Exchange page lookup)
     * @return ordered list of shop entries; empty if lookup fails or no shops found
     */
    public List<ShopEntry> lookupShops(String pageSlug, int storedGameId) {
        if (pageSlug == null || pageSlug.isBlank()) return Collections.emptyList();

        String wikitext = fetchWikitext(pageSlug);
        if (wikitext == null) {
            log.warn("WikiItemLookup: failed to fetch wikitext for '{}'", pageSlug);
            return Collections.emptyList();
        }

        // Extract game item ID from the Infobox on the item's own page: | id = 1381
        // This is more reliable than the Exchange page (which just transcludes a template).
        int resolvedGameId = storedGameId;
        if (resolvedGameId == 0) {
            Matcher idMatcher = INFOBOX_ID_RE.matcher(wikitext);
            if (idMatcher.find()) {
                try {
                    resolvedGameId = Integer.parseInt(idMatcher.group(1));
                    log.info("WikiItemLookup: extracted game ID {} from infobox for '{}'",
                            resolvedGameId, pageSlug);
                } catch (NumberFormatException ignored) { /* leave at 0 */ }
            }
        }

        // Extract base value from Infobox: | value = N
        int baseValue = 0;
        Matcher valueMatcher = INFOBOX_VALUE_RE.matcher(wikitext);
        if (valueMatcher.find()) {
            try {
                baseValue = Integer.parseInt(valueMatcher.group(1));
            } catch (NumberFormatException ignored) { /* leave at 0 */ }
        }

        List<ShopEntry> results = parseShopEntries(wikitext, resolvedGameId, baseValue);
        log.info("WikiItemLookup: '{}' → {} shop entries (gameId={}, value={})",
                pageSlug, results.size(), resolvedGameId, baseValue);
        return results;
    }

    /**
     * Fetch and parse the crafting recipe for an item from the OSRS wiki.
     *
     * <p>Looks for a wiki crafting/smithing/fletching/cooking template in the
     * wikitext ({@code {{Crafting|mat1=...|mat2=...|level=...|xp=...}}}),
     * extracts ingredient names + quantities, skill level, and XP.
     *
     * @param pageSlug wiki page slug, e.g. "Air_battlestaff"
     * @return parsed recipe, or {@code null} if none found
     */
    public CraftingRecipe lookupCraftingRecipe(String pageSlug) {
        if (pageSlug == null || pageSlug.isBlank()) return null;
        String wikitext = fetchWikitext(pageSlug);
        if (wikitext == null) {
            log.warn("WikiItemLookup.lookupCraftingRecipe: failed to fetch '{}'", pageSlug);
            return null;
        }

        // Detect which skill template is present — pick the one that appears earliest
        // in the wikitext so that pages with multiple craft templates (e.g. Cake has
        // both {{Cooking and {{Crafting) use the recipe that's actually documented first.
        String skill = null;
        String lowerWt = wikitext.toLowerCase(Locale.ROOT);
        int bestPos = Integer.MAX_VALUE;
        for (String[] pair : CRAFT_TEMPLATE_SKILLS) {
            int pos = lowerWt.indexOf(pair[0].toLowerCase(Locale.ROOT));
            if (pos >= 0 && pos < bestPos) {
                bestPos = pos;
                skill = pair[1];
            }
        }
        if (skill == null) {
            log.info("WikiItemLookup.lookupCraftingRecipe: no craft template in '{}'", pageSlug);
            return null;
        }

        // Extract mat name + qty pairs
        Map<String, String> matNameMap = new LinkedHashMap<>();
        Matcher matNameM = MAT_NAME_RE.matcher(wikitext);
        while (matNameM.find()) {
            String idx = matNameM.group(1);
            String raw = matNameM.group(2).trim();
            // Strip [[Link|Text]] → Text; [[Link]] → Link
            Matcher linkM = WIKI_LINK_RE.matcher(raw);
            String name;
            if (linkM.find()) {
                // Wiki link syntax present — use the display text
                name = linkM.group(1).trim();
            } else {
                // Inline template: next key may appear on same line separated by |
                // Truncate at the first | (next key) or { } (template boundary)
                name = raw.replaceAll("[|{}].*", "").trim();
            }
            name = name.replaceAll("<[^>]+>", "").trim(); // strip any html
            if (!name.isEmpty()) matNameMap.put(idx, name);
        }

        Map<String, Integer> matQtyMap = new HashMap<>();
        Matcher matQtyM = MAT_QTY_RE.matcher(wikitext);
        while (matQtyM.find()) {
            try { matQtyMap.put(matQtyM.group(1), Integer.parseInt(matQtyM.group(2).trim())); }
            catch (NumberFormatException ignored) { /* leave default */ }
        }

        List<MaterialEntry> materials = new ArrayList<>();
        for (Map.Entry<String, String> e : matNameMap.entrySet()) {
            int qty = matQtyMap.getOrDefault(e.getKey(), 1);
            materials.add(new MaterialEntry(e.getValue(), qty));
        }
        if (materials.isEmpty()) {
            log.info("WikiItemLookup.lookupCraftingRecipe: no mat entries in '{}'", pageSlug);
            return null;
        }

        // Level + XP
        int level = 1;
        Matcher levelM = CRAFT_LEVEL_RE.matcher(wikitext);
        if (levelM.find()) {
            try { level = Integer.parseInt(levelM.group(1).trim()); }
            catch (NumberFormatException ignored) { /* leave default */ }
        }
        double xp = 0;
        Matcher xpM = CRAFT_XP_RE.matcher(wikitext);
        if (xpM.find()) {
            try { xp = Double.parseDouble(xpM.group(1).trim()); }
            catch (NumberFormatException ignored) { /* leave default */ }
        }

        String skillLower = skill.toLowerCase(Locale.ROOT);
        WorldPoint craftLoc = SKILL_CRAFT_LOCATIONS.get(skillLower);
        String craftLocName = SKILL_CRAFT_LOCATION_NAMES.getOrDefault(skillLower, "anywhere");

        String craftedItem = pageSlug.replace('_', ' ');
        log.info("WikiItemLookup.lookupCraftingRecipe: '{}' → {} ({}) mats, {}lvl {}xp",
                pageSlug, materials.size(), skill, level, xp);
        return new CraftingRecipe(craftedItem, materials, skill, level, xp, craftLoc, craftLocName);
    }

    // -------------------------------------------------------------------------
    // Static utilities (craft alias / obtain location)
    // -------------------------------------------------------------------------

    /**
     * Returns the wiki page slug for the craftable form of an item name, or
     * {@code null} if no alias is registered. Used by {@link GoalPlanner} to
     * redirect "make a staff of air" → "Air_battlestaff".
     *
     * @param displayName the item name the player typed, e.g. "staff of air"
     */
    public static String craftAliasSlug(String displayName) {
        if (displayName == null) return null;
        return CRAFT_ALIASES.get(displayName.toLowerCase(Locale.ROOT).trim());
    }

    /**
     * Returns the WorldPoint of a known non-shop obtain location for an item,
     * or {@code null} if unknown. Used by {@link GoalPlanner} to build overlay
     * steps for ingredients that can't be bought in shops.
     *
     * @param lowerName lowercase item name, e.g. "air orb"
     */
    public static WorldPoint getObtainLocation(String lowerName) {
        return lowerName == null ? null : OBTAIN_LOCATIONS.get(lowerName.trim());
    }

    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Fetch wikitext for a given page slug. Returns null on failure. */
    String fetchWikitext(String pageSlug) {
        String url = String.format(WIKI_API, pageSlug.replace(" ", "_"));
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("WikiItemLookup: HTTP {} for '{}'", resp.code(), pageSlug);
                    return null;
                }
                String body = resp.body().string();
                JsonObject json = gson.fromJson(body, JsonObject.class);
                JsonObject parse = json.getAsJsonObject("parse");
                if (parse == null) return null;
                JsonObject wt = parse.getAsJsonObject("wikitext");
                if (wt == null) return null;
                com.google.gson.JsonElement star = wt.get("*");
                if (star == null || star.isJsonNull()) return null;
                return star.getAsString();
            }
        } catch (Exception e) {
            log.warn("WikiItemLookup: exception fetching '{}': {}", pageSlug, e.getMessage());
            return null;
        }
    }

    /**
     * Scan the wikitext for known shop names and build ShopEntry objects.
     * We check every entry in the static lookup table (lowercased) against the
     * lowercased wikitext — a simple {@code contains()} check is sufficient
     * because shop templates always include the shop name verbatim.
     *
     * @param wikitext      full wikitext of the item page
     * @param resolvedGameId OSRS game item ID (already extracted from the Infobox)
     * @param baseValue     base value from the Infobox (| value = N)
     */
    List<ShopEntry> parseShopEntries(String wikitext, int resolvedGameId, int baseValue) {
        if (wikitext == null || wikitext.isBlank()) return Collections.emptyList();

        String lower = wikitext.toLowerCase(Locale.ROOT);
        List<ShopEntry> out = new ArrayList<>();
        // Shop price ≈ 130% of base value (standard OSRS shop markup).
        int shopPrice = baseValue > 0 ? (int) Math.ceil(baseValue * 1.3) : 0;

        for (Map.Entry<String, WorldPoint> entry : SHOP_LOCATIONS.entrySet()) {
            String shopKey = entry.getKey();
            if (!lower.contains(shopKey)) continue;

            String npcName = SHOP_NPC_NAMES.get(shopKey);
            int npcId = npcName != null ? NPC_IDS.getOrDefault(npcName, -1) : -1;
            WorldPoint nearestBank = NEAREST_BANK.get(shopKey);
            String displayShopName = toTitleCase(shopKey);

            out.add(new ShopEntry(displayShopName, npcName, entry.getValue(),
                    nearestBank, npcId, resolvedGameId, shopPrice));
        }

        return out;
    }

    // -------------------------------------------------------------------------
    // Static utilities
    // -------------------------------------------------------------------------

    /**
     * Extract the wiki page slug from a full wiki URL.
     * {@code "https://oldschool.runescape.wiki/w/Staff_of_air"} → {@code "Staff_of_air"}.
     */
    public static String pageSlugFromUrl(String wikiUrl) {
        if (wikiUrl == null || wikiUrl.isBlank()) return null;
        int idx = wikiUrl.lastIndexOf('/');
        return idx >= 0 ? wikiUrl.substring(idx + 1) : wikiUrl;
    }

    private static String toTitleCase(String s) {
        if (s == null || s.isBlank()) return s;
        String[] words = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) sb.append(w.substring(1));
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }
}
