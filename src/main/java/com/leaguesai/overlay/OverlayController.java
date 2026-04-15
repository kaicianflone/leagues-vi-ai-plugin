package com.leaguesai.overlay;

import com.leaguesai.agent.PlannedStep;
import com.leaguesai.data.model.Task;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;

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
    private final RequiredItemsOverlay requiredItemsOverlay;
    private final StepInstructionOverlay stepInstructionOverlay;

    // Track plan size for the HUD step counter
    private volatile int planTotal = 0;
    private volatile int planIndex = 0;

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
        WidgetOverlay widgetOverlay,
        RequiredItemsOverlay requiredItemsOverlay,
        StepInstructionOverlay stepInstructionOverlay
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
        this.requiredItemsOverlay = requiredItemsOverlay;
        this.stepInstructionOverlay = stepInstructionOverlay;
    }

    /** Call once when a new plan is activated so the HUD shows correct N/M. */
    public void setPlanSize(int total) {
        this.planTotal = total;
        this.planIndex = 0;
    }

    public void setActiveStep(PlannedStep step) {
        setActiveStep(step, planIndex);
    }

    public void setActiveStep(PlannedStep step, int index) {
        clearAll();
        this.planIndex = index;
        if (step == null) {
            return;
        }
        // HUD instruction panel — always shown regardless of spatial data
        stepInstructionOverlay.setActiveStep(step, index, planTotal);
        OverlayData data = step.getOverlayData();
        if (data == null) {
            return;
        }
        // targetTile = precise scraped location (tile highlight only).
        // destination = precise OR area hub fallback (arrow, minimap, world map).
        net.runelite.api.coords.WorldPoint waypointTile =
                data.getTargetTile() != null ? data.getTargetTile() : step.getDestination();

        if (data.getTargetTile() != null) {
            tileHighlightOverlay.setTargetTile(data.getTargetTile());
        }
        if (waypointTile != null) {
            if (data.isShowArrow()) {
                arrowOverlay.setTargetTile(waypointTile);
            }
            if (data.isShowMinimap()) {
                minimapOverlay.setTargetPoint(waypointTile);
            }
            if (data.isShowWorldMap()) {
                worldMapOverlay.update(waypointTile, null);
            }
        }
        if (data.getTargetNpcIds() != null && !data.getTargetNpcIds().isEmpty()) {
            npcHighlightOverlay.setTargetNpcIds(data.getTargetNpcIds());
        }
        if (data.getTargetNpcNames() != null && !data.getTargetNpcNames().isEmpty()) {
            npcHighlightOverlay.setTargetNpcNames(data.getTargetNpcNames());
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
        if (data.getShopItemGameIds() != null && !data.getShopItemGameIds().isEmpty()) {
            widgetOverlay.setShopItemGameIds(data.getShopItemGameIds());
        }

        // Required items panel — pulled from the task itself, not OverlayData,
        // because items are intrinsic to the task spec from the scraper.
        Task task = step.getTask();
        if (task != null && task.getItemsRequired() != null && !task.getItemsRequired().isEmpty()) {
            requiredItemsOverlay.setRequiredItems(task.getItemsRequired());
        } else {
            requiredItemsOverlay.setRequiredItems(Collections.emptyMap());
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
        requiredItemsOverlay.clear();
        stepInstructionOverlay.clear();
    }
}
