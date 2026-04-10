package com.leaguesai.scraper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts item targets from task names and descriptions using a strict verb
 * allowlist to minimise false positives.
 *
 * <p>Only the following verbs trigger extraction: equip, obtain, acquire, wear,
 * wield. This is intentional — broader verb lists produce too many false positives
 * against generic task descriptions.
 */
public class TaskItemExtractor {

    /**
     * Matches a verb from the allowlist followed by an optional article and then
     * the item name. The item name capture stops at common prepositions, sentence
     * boundary punctuation, or end-of-input.
     */
    private static final Pattern ITEM_PATTERN = Pattern.compile(
        "(?i)(equip|obtain|acquire|wear|wield)" +
        "\\s+(?:a\\s+|an\\s+|the\\s+|some\\s+)?" +
        "([A-Za-z][A-Za-z '()+\\-]+?)" +
        "(?=\\s+(?:in|on|at|from|to|and|or|for|while|with|using)\\b|[,.\\n]|$)"
    );

    /**
     * Extracts item targets from a task name and description. The task name is
     * checked first (a verb match in the name is high-confidence). The description
     * is also scanned. Results are deduplicated by lowercased name.
     *
     * @param taskName    task name; may be null
     * @param description task description; may be null
     * @return deduplicated list of {@link ItemTarget}; never null, may be empty
     */
    public List<ItemTarget> extract(String taskName, String description) {
        // Use LinkedHashMap to preserve insertion order while deduplicating.
        Map<String, ItemTarget> seen = new LinkedHashMap<>();

        scanText(taskName, seen);
        scanText(description, seen);

        return new ArrayList<>(seen.values());
    }

    private void scanText(String text, Map<String, ItemTarget> seen) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher m = ITEM_PATTERN.matcher(text);
        while (m.find()) {
            String raw = m.group(2).trim();
            if (!raw.isEmpty()) {
                String key = raw.toLowerCase();
                if (!seen.containsKey(key)) {
                    seen.put(key, new ItemTarget(raw));
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    /**
     * An item name extracted from task text. {@code wikiItemId} starts at 0
     * and is resolved to a real OSRS Wiki item ID by {@code ItemStatsScraper}
     * in a later phase.
     */
    public static class ItemTarget {
        public final String name;
        public int wikiItemId;  // 0 until resolved

        public ItemTarget(String name) {
            this.name = name;
        }
    }
}
