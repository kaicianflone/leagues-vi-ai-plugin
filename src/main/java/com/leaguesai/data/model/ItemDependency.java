package com.leaguesai.data.model;

import com.leaguesai.agent.ObtainMethod;
import lombok.Builder;
import lombok.Value;

/**
 * Immutable model representing one row in the {@code item_dependencies} table.
 * A single item may have multiple rows (e.g. smithed AND dropped variants).
 */
@Value
@Builder
public class ItemDependency {

    /**
     * Slugified wiki page name for the output item.
     * Example: "Rune platebody" → "rune_platebody" (lowercase, spaces to underscores, apostrophes stripped).
     */
    String itemId;

    /** Display name of the output item. Example: "Rune platebody". */
    String itemName;

    /** How this item is obtained; drives BFS recursion in ItemDependencyGraph. */
    ObtainMethod obtainMethod;

    /**
     * If {@link ObtainMethod#isRecursable()} is true, this is the slugified item_id of
     * the ingredient. Otherwise it is the name of the entity that provides it
     * (monster name, quest name, shop name, etc.).
     */
    String sourceId;

    /** Display name of the source (ingredient item, monster, quest, shop, etc.). */
    String sourceName;

    /**
     * Skill required to perform the recipe (e.g. "Smithing", "Crafting").
     * {@code null} for terminal obtain methods.
     */
    String skillRequired;

    /**
     * Minimum skill level required. {@code 0} if not applicable (terminal methods).
     */
    int skillLevel;

    /** Quantity of the source item required per craft/output batch. */
    int qtyNeeded;

    /**
     * Number of output items produced per craft. Defaults to 1.
     * Use {@code @Builder.Default} is intentionally omitted — callers must set this explicitly
     * or accept 0; loadFromDb() always reads the column value from the DB.
     */
    int outputQty;

    /**
     * Area that must be unlocked before this obtain method is accessible.
     * {@code null} until launch-day area scraper fills it in.
     */
    String areaRequired;
}
