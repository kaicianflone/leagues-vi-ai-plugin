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
            this.nameToId = Collections.unmodifiableMap(newNameToId);

            // Pre-sort names longest-first so findLongestMatchingItem can break on first match.
            List<String> sorted = new ArrayList<>(newNameToId.keySet());
            sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
            this.sortedNamesLongestFirst = Collections.unmodifiableList(sorted);

            log.info("ItemDependencyGraph: loaded {} item dependencies ({} unique items)",
                    totalRows, this.graph.size());

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
     * @param name display name, e.g. "Rune platebody", or slug "rune_platebody"
     * @return the first {@link ItemDependency} row for the matched item, or
     *         {@code null} if not found
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
        if (deps == null || deps.isEmpty()) {
            return null;
        }
        return deps.get(0);
    }

    /**
     * Scans {@code lowerPhrase} for the longest item name it contains and returns
     * the corresponding {@link ItemDependency}, or {@code null} if no known item
     * name appears in the phrase.
     *
     * <p>Uses a pre-sorted (longest-first) name list so the first match found is
     * always the longest — O(k) where k is the index of the match rather than
     * O(n) over the full name set.
     *
     * @param lowerPhrase the search phrase, already lowercased and trimmed
     */
    public ItemDependency findLongestMatchingItem(String lowerPhrase) {
        if (lowerPhrase == null || lowerPhrase.isEmpty()) return null;
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
     * Returns {@code true} if no item dependencies have been loaded.
     * Useful for graceful UI handling when the DB is empty or missing.
     */
    public boolean isEmpty() {
        return graph.isEmpty();
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
