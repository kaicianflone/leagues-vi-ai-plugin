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
    private final List<Integer> targetObjectIds;
    private final List<Integer> targetItemIds;
    private final List<WorldPoint> pathPoints;
    private final List<Integer> widgetIds;
    private final boolean showArrow;
    private final boolean showMinimap;
    private final boolean showWorldMap;
}
