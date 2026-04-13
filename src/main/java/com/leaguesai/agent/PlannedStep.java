package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import com.leaguesai.overlay.OverlayData;
import com.leaguesai.ui.AnimationType;
import lombok.Builder;
import lombok.Data;
import net.runelite.api.coords.WorldPoint;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class PlannedStep {
    private final Task task;
    private final WorldPoint destination;
    private final List<Integer> requiredItems;
    private final String instruction;
    private final OverlayData overlayData;
    private final AnimationType animation;

    /**
     * Item-name → "best ironman acquisition path" sentence, populated by
     * {@link ItemSourceResolver} once after the planner builds the plan.
     * Keyed by display name (matches {@code Task.itemsRequired} keys).
     * Empty (not null) when resolution failed or no items are required.
     */
    private final Map<String, String> itemSourceNotes;

    /**
     * OSRS game item IDs that, when detected in the player's inventory, indicate
     * this step is complete. Populated only for wiki-lookup steps (shop/bank):
     * <ul>
     *   <li>"Withdraw coins" step → [995] (coins item ID)</li>
     *   <li>"Buy X from shop" step → [targetItemGameId]</li>
     * </ul>
     * Empty for Task-based steps (they use task-completion events instead).
     */
    private final List<Integer> completionItemIds;

    /**
     * Minimum quantity per item ID required for {@link #completionItemIds} to be
     * considered satisfied. If an ID is absent from this map, a quantity of 1 is
     * assumed. Used by the bank step to require {@code coins >= shopValue} rather
     * than just {@code coins > 0}.
     */
    private final Map<Integer, Integer> completionItemMinQtys;
}
