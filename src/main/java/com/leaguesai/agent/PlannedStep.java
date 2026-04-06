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
}
