package com.leaguesai.overlay;

import com.leaguesai.agent.PlannedStep;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class OverlayController {

    private final TileHighlightOverlay tileHighlightOverlay;
    private final ArrowOverlay arrowOverlay;
    private final NpcHighlightOverlay npcHighlightOverlay;
    private final ObjectHighlightOverlay objectHighlightOverlay;
    private final GroundItemOverlay groundItemOverlay;
    private final MinimapOverlay minimapOverlay;
    private final WorldMapOverlay worldMapOverlay;
    private final PathOverlay pathOverlay;
    private final WidgetOverlay widgetOverlay;

    @Inject
    public OverlayController(
        TileHighlightOverlay tileHighlightOverlay,
        ArrowOverlay arrowOverlay,
        NpcHighlightOverlay npcHighlightOverlay,
        ObjectHighlightOverlay objectHighlightOverlay,
        GroundItemOverlay groundItemOverlay,
        MinimapOverlay minimapOverlay,
        WorldMapOverlay worldMapOverlay,
        PathOverlay pathOverlay,
        WidgetOverlay widgetOverlay
    ) {
        this.tileHighlightOverlay = tileHighlightOverlay;
        this.arrowOverlay = arrowOverlay;
        this.npcHighlightOverlay = npcHighlightOverlay;
        this.objectHighlightOverlay = objectHighlightOverlay;
        this.groundItemOverlay = groundItemOverlay;
        this.minimapOverlay = minimapOverlay;
        this.worldMapOverlay = worldMapOverlay;
        this.pathOverlay = pathOverlay;
        this.widgetOverlay = widgetOverlay;
    }

    public void setActiveStep(PlannedStep step) {
        clearAll();
        if (step == null) {
            return;
        }
        OverlayData data = step.getOverlayData();
        if (data == null) {
            return;
        }
        if (data.getTargetTile() != null) {
            tileHighlightOverlay.setTargetTile(data.getTargetTile());
            if (data.isShowArrow()) {
                arrowOverlay.setTargetTile(data.getTargetTile());
            }
            if (data.isShowMinimap()) {
                minimapOverlay.setTargetPoint(data.getTargetTile());
            }
            if (data.isShowWorldMap()) {
                worldMapOverlay.update(data.getTargetTile(), null);
            }
        }
        if (data.getTargetNpcIds() != null && !data.getTargetNpcIds().isEmpty()) {
            npcHighlightOverlay.setTargetNpcIds(data.getTargetNpcIds());
        }
        if (data.getTargetObjectIds() != null && !data.getTargetObjectIds().isEmpty()) {
            objectHighlightOverlay.setTargetObjectIds(data.getTargetObjectIds());
        }
        if (data.getTargetItemIds() != null && !data.getTargetItemIds().isEmpty()) {
            groundItemOverlay.setTargetItemIds(data.getTargetItemIds());
        }
        if (data.getPathPoints() != null && data.getPathPoints().size() >= 2) {
            pathOverlay.setPathPoints(data.getPathPoints());
        }
        if (data.getWidgetIds() != null && !data.getWidgetIds().isEmpty()) {
            widgetOverlay.setTargetWidgetIds(data.getWidgetIds());
        }
    }

    public void clearAll() {
        tileHighlightOverlay.clear();
        arrowOverlay.clear();
        npcHighlightOverlay.clear();
        objectHighlightOverlay.clear();
        groundItemOverlay.clear();
        minimapOverlay.clear();
        worldMapOverlay.clear();
        pathOverlay.clear();
        widgetOverlay.clear();
    }
}
