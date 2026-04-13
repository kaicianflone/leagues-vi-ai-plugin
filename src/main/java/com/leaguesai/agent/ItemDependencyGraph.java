package com.leaguesai.agent;

import com.leaguesai.data.model.ItemDependency;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory item dependency graph backed by the {@code item_dependencies} SQLite table.
 *
 * <p>Call {@link #loadFromDb()} once on a background thread (e.g. from
 * {@code LeaguesAiPlugin.loadDatabaseAsync()}) before calling any query methods.
 * After loading completes, all read operations are safe for concurrent access —
 * the internal maps are replaced atomically with unmodifiable views.
 *
 * <p>This class is NOT Guice-injected. Construct it manually in LeaguesAiPlugin
 * the same way DatabaseLoader is constructed.
 */
@Slf4j
public class ItemDependencyGraph {

    /** Maximum BFS depth to prevent runaway expansion on malformed/circular data. */
    private static final int MAX_DEPTH = 20;

    private final File dbFile;

    /** itemId → unmodifiable list of all dependency rows for that item. */
    private volatile Map<String, List<ItemDependency>> graph = Collections.emptyMap();

    /** Normalized lowercase item name / slug → itemId. */
    private volatile Map<String, String> nameToId = Collections.emptyMap();

    /**
     * All known item names sorted longest-first. Cached after {@link #loadFromDb()} so
     * {@link #findLongestMatchingItem} can break on the first match instead of
     * iterating every name to find the longest one.
     */
    private volatile List<String> sortedNamesLongestFirst = Collections.emptyList();

    /**
     * Items from the {@code items} equipment catalog that have no rows in
     * {@code item_dependencies} yet. Keyed by item slug.
     * itemId → wiki URL
     */
    private volatile Map<String, String> catalogWikiUrls = Collections.emptyMap();

    /** itemId → display name (from the items catalog). */
    private volatile Map<String, String> catalogDisplayNames = Collections.emptyMap();

    /** itemId → OSRS game item ID (wiki_item_id column; 0 means unknown). */
    private volatile Map<String, Integer> catalogGameIds = Collections.emptyMap();

    public ItemDependencyGraph(File dbFile) {
        this.dbFile = dbFile;
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /**
     * Opens the SQLite database, creates the {@code item_dependencies} table if
     * absent, reads all rows, and builds the in-memory graph. Safe to call on a
     * background thread. On any failure the internal maps remain empty (graceful
     * degradation — the UI falls back to showing no ingredient tree).
     */
    public void loadFromDb() {
        if (dbFile == null || !dbFile.exists()) {
            log.info("ItemDependencyGraph: db file not found ({}), graph will be empty",
                    dbFile == null ? "null" : dbFile.getAbsolutePath());
            return;
        }

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + dbFile.getAbsolutePath())) {

            // Ensure the table exists so loadFromDb() is idempotent on a fresh DB.
            try (Statement ddl = conn.createStatement()) {
                ddl.execute(
                    "CREATE TABLE IF NOT EXISTS item_dependencies (" +
                    "  item_id        TEXT NOT NULL," +
                    "  item_name      TEXT NOT NULL," +
                    "  obtain_method  TEXT NOT NULL," +
                    "  source_id      TEXT," +
                    "  source_name    TEXT," +
                    "  skill_required TEXT," +
                    "  skill_level    INTEGER DEFAULT 0," +
                    "  qty_needed     INTEGER DEFAULT 1," +
                    "  output_qty     INTEGER DEFAULT 1," +
                    "  area_required  TEXT," +
                    "  PRIMARY KEY (item_id, obtain_method, source_id)" +
                    ")"
                );
            }

            Map<String, List<ItemDependency>> newGraph = new LinkedHashMap<>();
            int totalRows = 0;

            try (Statement sel = conn.createStatement();
                 ResultSet rs = sel.executeQuery("SELECT * FROM item_dependencies")) {

                while (rs.next()) {
                    String obtainMethodStr = rs.getString("obtain_method");
                    ObtainMethod obtainMethod;
                    try {
                        obtainMethod = ObtainMethod.valueOf(obtainMethodStr);
                    } catch (IllegalArgumentException | NullPointerException e) {
                        log.warn("ItemDependencyGraph: unknown obtain_method '{}', skipping row",
                                obtainMethodStr);
                        continue;
                    }

                    ItemDependency dep = ItemDependency.builder()
                            .itemId(rs.getString("item_id"))
                            .itemName(rs.getString("item_name"))
                            .obtainMethod(obtainMethod)
                            .sourceId(rs.getString("source_id"))
                            .sourceName(rs.getString("source_name"))
                            .skillRequired(rs.getString("skill_required"))
                            .skillLevel(rs.getInt("skill_level"))
                            .qtyNeeded(rs.getInt("qty_needed"))
                            .outputQty(rs.getInt("output_qty"))
                            .areaRequired(rs.getString("area_required"))
                            .build();

                    newGraph.computeIfAbsent(dep.getItemId(), k -> new ArrayList<>()).add(dep);
                    totalRows++;
                }
            }

            // Build nameToId lookup
            Map<String, String> newNameToId = new HashMap<>();
            for (Map.Entry<String, List<ItemDependency>> entry : newGraph.entrySet()) {
                String itemId = entry.getKey();
                for (ItemDependency dep : entry.getValue()) {
                    if (dep.getItemName() != null) {
                        newNameToId.put(dep.getItemName().toLowerCase(Locale.ROOT).trim(), itemId);
                    }
                    // Also map the slug form (underscores → spaces)
                    newNameToId.put(itemId.replace('_', ' '), itemId);
                }
            }

            // Make everything unmodifiable and swap atomically
            Map<String, List<ItemDependency>> immutableGraph = new LinkedHashMap<>();
            for (Map.Entry<String, List<ItemDependency>> entry : newGraph.entrySet()) {
                immutableGraph.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
            }
            this.graph = Collections.unmodifiableMap(immutableGraph);

            // Load the equipment items catalog (4000+ items with wiki URLs) so that
            // name matching fires even when item_dependencies is empty. Items that are
            // in both tables are skipped in favor of the richer item_dependencies data.
            Map<String, String> newCatalogWikiUrls = new HashMap<>();
            Map<String, String> newCatalogDisplayNames = new HashMap<>();
            Map<String, Integer> newCatalogGameIds = new HashMap<>();
            try (Statement catSel = conn.createStatement();
                 ResultSet catRs = catSel.executeQuery(
                         "SELECT id, wiki_item_id, name, wiki_url FROM items " +
                         "WHERE wiki_url IS NOT NULL AND wiki_url != ''")) {
                while (catRs.next()) {
                    String itemId = catRs.getString("id");
                    if (itemId == null || newGraph.containsKey(itemId)) continue;
                    String displayName = catRs.getString("name");
                    String wikiUrl = catRs.getString("wiki_url");
                    int gameId = catRs.getInt("wiki_item_id");
                    newCatalogWikiUrls.put(itemId, wikiUrl);
                    if (displayName != null) newCatalogDisplayNames.put(itemId, displayName);
                    newCatalogGameIds.put(itemId, gameId);
                    // Add to nameToId so GoalSpecParser and findLongestMatchingItem can resolve these.
                    // Use putIfAbsent: item_dependencies rows take priority over catalog stubs.
                    // Two different itemIds can produce the same normalized name — the richer
                    // item_dependencies entry (loaded first) should win.
                    if (displayName != null) {
                        newNameToId.putIfAbsent(displayName.toLowerCase(Locale.ROOT).trim(), itemId);
                    }
                    newNameToId.putIfAbsent(itemId.replace('_', ' '), itemId);
                }
            } catch (Exception catEx) {
                log.warn("ItemDependencyGraph: failed to load items catalog: {}", catEx.getMessage());
            }
            this.catalogWikiUrls = Collections.unmodifiableMap(newCatalogWikiUrls);
            this.catalogDisplayNames = Collections.unmodifiableMap(newCatalogDisplayNames);
            this.catalogGameIds = Collections.unmodifiableMap(newCatalogGameIds);

            this.nameToId = Collections.unmodifiableMap(newNameToId);

            // Pre-sort names longest-first so findLongestMatchingItem can break on first match.
            List<String> sorted = new ArrayList<>(newNameToId.keySet());
            sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
            this.sortedNamesLongestFirst = Collections.unmodifiableList(sorted);

            log.info("ItemDependencyGraph: loaded {} item dependencies ({} unique items) + {} catalog items",
                    totalRows, this.graph.size(), newCatalogWikiUrls.size());

        } catch (Exception e) {
            log.warn("ItemDependencyGraph: failed to load from DB — graph will be empty. Cause: {}",
                    e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * BFS expansion of {@code itemId}: returns all dependency rows reachable
     * from the given item, in prerequisite-first order (ingredients before the
     * items that require them).
     *
     * <p>Cycle-safe: each itemId is only visited once. Depth-capped at
     * {@link #MAX_DEPTH} to handle any degenerate data.
     *
     * @param itemId slugified item id (e.g. {@code "rune_platebody"})
     * @return ordered list of {@link ItemDependency} rows, empty if unknown
     */
    public List<ItemDependency> expand(String itemId) {
        if (itemId == null || !graph.containsKey(itemId)) {
            return Collections.emptyList();
        }

        List<ItemDependency> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        // Queue entries: [itemId, depth]
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{itemId, 0});

        while (!queue.isEmpty()) {
            Object[] entry = queue.poll();
            String current = (String) entry[0];
            int depth = (int) entry[1];

            if (visited.contains(current)) {
                continue;
            }
            visited.add(current);

            List<ItemDependency> deps = graph.get(current);
            if (deps == null) {
                continue;
            }

            for (ItemDependency dep : deps) {
                result.add(dep);
                if (dep.getObtainMethod().isRecursable()) {
                    String srcId = dep.getSourceId();
                    if (srcId != null && !visited.contains(srcId)) {
                        if (depth + 1 < MAX_DEPTH) {
                            queue.add(new Object[]{srcId, depth + 1});
                        } else {
                            log.warn("ItemDependencyGraph.expand: MAX_DEPTH ({}) reached at item '{}', " +
                                    "stopping BFS", MAX_DEPTH, current);
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * Looks up an item by display name (case-insensitive) or slug. Used by
     * {@code GoalSpecParser} to resolve player-typed item names.
     *
     * <p>For items that exist in the equipment catalog ({@code items} table) but
     * have no rows in {@code item_dependencies}, returns a stub
     * {@link ItemDependency} with {@link ObtainMethod#SHOP} so that the parser
     * can build an ITEM {@link GoalSpec} and route to {@link WikiItemLookup}.
     *
     * @param name display name, e.g. "Rune platebody", or slug "rune_platebody"
     * @return the first dependency row, a catalog stub, or {@code null} if unknown
     */
    public ItemDependency findItemByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.toLowerCase(Locale.ROOT).trim();
        String itemId = nameToId.get(normalized);
        if (itemId == null) {
            return null;
        }
        List<ItemDependency> deps = graph.get(itemId);
        if (deps != null && !deps.isEmpty()) {
            return deps.get(0);
        }
        // Catalog-only item: return a stub so the parser can build an ITEM GoalSpec.
        if (catalogWikiUrls.containsKey(itemId)) {
            String displayName = catalogDisplayNames.getOrDefault(itemId, name);
            return ItemDependency.builder()
                    .itemId(itemId)
                    .itemName(displayName)
                    .obtainMethod(ObtainMethod.SHOP)
                    .sourceId(null)
                    .sourceName(null)
                    .skillRequired(null)
                    .skillLevel(0)
                    .qtyNeeded(1)
                    .outputQty(1)
                    .areaRequired(null)
                    .build();
        }
        return null;
    }

    /** Stop words ignored when doing bag-of-words item matching. */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "of", "the", "a", "an", "and", "with", "from", "to", "in", "on", "at", "for"
    ));

    /**
     * Scans {@code lowerPhrase} for the longest item name it contains and returns
     * the corresponding {@link ItemDependency}, or {@code null} if no known item
     * name appears in the phrase.
     *
     * <p>Two passes:
     * <ol>
     *   <li><b>Bag-of-words pass</b> (order-agnostic) — finds multi-word items
     *       whose content words all appear in the phrase regardless of order.
     *       Handles "air staff" matching "Staff of air". Requires at least 2
     *       content words to avoid false positives on single-word names.</li>
     *   <li><b>Substring pass</b> (fallback) — exact substring match, longest-first,
     *       for single-word items and exact phrases.</li>
     * </ol>
     *
     * @param lowerPhrase the search phrase, already lowercased and trimmed
     */
    public ItemDependency findLongestMatchingItem(String lowerPhrase) {
        if (lowerPhrase == null || lowerPhrase.isEmpty()) return null;

        // Pass 1: bag-of-words (order-agnostic). All content words of the item
        // name must appear somewhere in the phrase words. Pick the name with the
        // most content words (longest meaningful match).
        Set<String> phraseWords = new HashSet<>(Arrays.asList(lowerPhrase.split("\\W+")));
        String bestBowName = null;
        int bestBowScore = 1; // require at least 2 content words
        for (String name : sortedNamesLongestFirst) {
            String[] words = name.split("\\W+");
            int contentCount = 0;
            int matchCount = 0;
            for (String w : words) {
                if (w.length() <= 1 || STOP_WORDS.contains(w)) continue;
                contentCount++;
                if (phraseWords.contains(w)) matchCount++;
            }
            if (contentCount >= 2 && matchCount == contentCount && contentCount > bestBowScore) {
                bestBowName = name;
                bestBowScore = contentCount;
            }
        }
        if (bestBowName != null) {
            ItemDependency dep = findItemByName(bestBowName);
            if (dep != null) return dep;
        }

        // Pass 2: exact substring match, longest-first (handles single-word items
        // and phrases where the item name appears verbatim).
        for (String name : sortedNamesLongestFirst) {
            if (lowerPhrase.contains(name)) {
                ItemDependency dep = findItemByName(name);
                if (dep != null) return dep;
            }
        }
        return null;
    }

    /**
     * Returns the set of all known item name keys (lowercase, trimmed) in the
     * name-to-id lookup. Used by {@code GoalSpecParser} to scan for item names
     * contained within a natural-language phrase.
     */
    public java.util.Set<String> knownNames() {
        return nameToId.keySet();
    }

    /**
     * Returns {@code true} if neither item dependencies nor the equipment catalog
     * have been loaded. After a successful {@link #loadFromDb()} call this will
     * return {@code false} even when {@code item_dependencies} is empty, because
     * the 4000+ item equipment catalog is also loaded.
     */
    public boolean isEmpty() {
        return graph.isEmpty() && catalogWikiUrls.isEmpty();
    }

    /**
     * Returns {@code true} if the item slug is present in the equipment catalog
     * ({@code items} table) but has no rows in {@code item_dependencies}.
     * Used by {@link GoalPlanner} to decide whether to call {@link WikiItemLookup}.
     */
    public boolean isInCatalog(String itemId) {
        return itemId != null && catalogWikiUrls.containsKey(itemId);
    }

    /**
     * Returns the wiki URL for a catalog item, or {@code null} if not found.
     * e.g. "https://oldschool.runescape.wiki/w/Staff_of_air".
     */
    public String getWikiUrl(String itemId) {
        return itemId == null ? null : catalogWikiUrls.get(itemId);
    }

    /**
     * Returns the display name for a catalog item, or {@code null} if not found.
     */
    public String getCatalogDisplayName(String itemId) {
        return itemId == null ? null : catalogDisplayNames.get(itemId);
    }

    /**
     * Returns the OSRS game item ID for a catalog item (the {@code wiki_item_id}
     * column). Returns 0 if unknown.
     */
    public int getCatalogGameId(String itemId) {
        if (itemId == null) return 0;
        Integer id = catalogGameIds.get(itemId);
        return id != null ? id : 0;
    }

    /**
     * Returns the total number of dependency rows across all items in the graph.
     */
    public int size() {
        int total = 0;
        for (List<ItemDependency> deps : graph.values()) {
            total += deps.size();
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a display name into a slug suitable for use as an {@code item_id}.
     * Example: "Rune platebody" → "rune_platebody", "Dragon's" → "dragons".
     */
    static String slugify(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT)
                   .replace("'", "")
                   .replace(" ", "_")
                   .replaceAll("[^a-z0-9_]", "");
    }
}
