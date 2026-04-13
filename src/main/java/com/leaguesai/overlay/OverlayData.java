package com.leaguesai.overlay;

import lombok.Builder;
import lombok.Data;
import net.runelite.api.coords.WorldPoint;
import java.util.List;

@Data
@Builder
public class OverlayData {
    private final WorldPoint targetTile;
    private final List<Integer> targetNpcIds;

    /**
     * NPC display names for {@link NpcHighlightOverlay} name-based matching.
     * Used when the NPC game ID is unknown (e.g., wiki lookup found NPC name
     * but not its RuneLite integer ID). Overlay scans {@code client.getNpcs()}
     * by {@code npc.getName()} at render time.
     */
    private final List<String> targetNpcNames;

    private final List<Integer> targetObjectIds;
    private final List<Integer> targetItemIds;
    private final List<WorldPoint> pathPoints;
    private final List<Integer> widgetIds;

    /**
     * OSRS game item IDs to highlight inside the shop interface (interface 300).
     * {@link WidgetOverlay} scans the shop container's dynamic children at render
     * time, matching by {@code widget.getItemId()}, and draws a highlight box
     * exactly like Quest Helper's shop item highlighting.
     */
    private final List<Integer> shopItemGameIds;

    private final boolean showArrow;
    private final boolean showMinimap;
    private final boolean showWorldMap;
}
