package com.leaguesai.overlay;

import com.leaguesai.agent.PlannedStep;
import com.leaguesai.data.model.Task;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class OverlayControllerTest {

    @Mock private TileHighlightOverlay tile;
    @Mock private ArrowOverlay arrow;
    @Mock private NpcHighlightOverlay npc;
    @Mock private ObjectHighlightOverlay object;
    @Mock private GroundItemOverlay item;
    @Mock private MinimapOverlay minimap;
    @Mock private WorldMapOverlay worldMap;
    @Mock private PathOverlay path;
    @Mock private WidgetOverlay widget;
    @Mock private RequiredItemsOverlay requiredItems;

    private OverlayController controller;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new OverlayController(tile, arrow, npc, object, item, minimap, worldMap, path, widget, requiredItems);
    }

    @Test
    public void clearAll_clearsEveryOverlay() {
        controller.clearAll();
        verify(tile).clear();
        verify(arrow).clear();
        verify(npc).clear();
        verify(object).clear();
        verify(item).clear();
        verify(minimap).clear();
        verify(worldMap).clear();
        verify(path).clear();
        verify(widget).clear();
        verify(requiredItems).clear();
    }

    @Test
    public void setActiveStep_taskWithItemsRequired_setsRequiredItemsOverlay() {
        Map<String, Integer> items = Map.of("Tinderbox", 1, "Bronze axe", 1);
        Task t = Task.builder().id("t1").name("Chop a tree")
                .itemsRequired(items).build();
        OverlayData data = OverlayData.builder().build();
        PlannedStep step = PlannedStep.builder().task(t).overlayData(data).build();
        controller.setActiveStep(step);
        verify(requiredItems).setRequiredItems(items);
    }

    @Test
    public void setActiveStep_taskWithNoItemsRequired_clearsRequiredItemsOverlay() {
        Task t = Task.builder().id("t1").name("Run").build();
        OverlayData data = OverlayData.builder().build();
        PlannedStep step = PlannedStep.builder().task(t).overlayData(data).build();
        controller.setActiveStep(step);
        verify(requiredItems).setRequiredItems(Collections.emptyMap());
    }

    @Test
    public void setActiveStep_null_clearsAll() {
        controller.setActiveStep(null);
        verify(tile).clear();
        verify(widget).clear();
        verify(tile, never()).setTargetTile(any());
    }

    @Test
    public void setActiveStep_nullOverlayData_clearsAll() {
        PlannedStep step = PlannedStep.builder().overlayData(null).build();
        controller.setActiveStep(step);
        verify(tile).clear();
    }

    @Test
    public void setActiveStep_setsTileTargetWhenPresent() {
        WorldPoint wp = new WorldPoint(3200, 3200, 0);
        OverlayData data = OverlayData.builder().targetTile(wp).build();
        PlannedStep step = PlannedStep.builder().overlayData(data).build();
        controller.setActiveStep(step);
        verify(tile).setTargetTile(wp);
        verify(arrow, never()).setTargetTile(any());
        verify(minimap, never()).setTargetPoint(any());
    }

    @Test
    public void setActiveStep_showArrowFlag_setsArrow() {
        WorldPoint wp = new WorldPoint(1, 2, 0);
        OverlayData data = OverlayData.builder().targetTile(wp).showArrow(true).build();
        controller.setActiveStep(PlannedStep.builder().overlayData(data).build());
        verify(arrow).setTargetTile(wp);
    }

    @Test
    public void setActiveStep_showMinimapFlag_setsMinimap() {
        WorldPoint wp = new WorldPoint(1, 2, 0);
        OverlayData data = OverlayData.builder().targetTile(wp).showMinimap(true).build();
        controller.setActiveStep(PlannedStep.builder().overlayData(data).build());
        verify(minimap).setTargetPoint(wp);
    }

    @Test
    public void setActiveStep_showWorldMapFlag_updatesWorldMap() {
        WorldPoint wp = new WorldPoint(1, 2, 0);
        OverlayData data = OverlayData.builder().targetTile(wp).showWorldMap(true).build();
        controller.setActiveStep(PlannedStep.builder().overlayData(data).build());
        verify(worldMap).update(eq(wp), any());
    }

    @Test
    public void setActiveStep_npcIds_setNpcOverlay() {
        OverlayData data = OverlayData.builder().targetNpcIds(Arrays.asList(1, 2)).build();
        controller.setActiveStep(PlannedStep.builder().overlayData(data).build());
        verify(npc).setTargetNpcIds(Arrays.asList(1, 2));
    }

    @Test
    public void setActiveStep_emptyNpcIds_doesNotSet() {
        OverlayData data = OverlayData.builder().targetNpcIds(Collections.emptyList()).build();
        controller.setActiveStep(PlannedStep.builder().overlayData(data).build());
        verify(npc, never()).setTargetNpcIds(anyList());
    }

    @Test
    public void setActiveStep_objectIds_setObjectOverlay() {
        OverlayData data = OverlayData.builder().targetObjectIds(Arrays.asList(100)).build();
        controller.setActiveStep(PlannedStep.builder().overlayData(data).build());
        verify(object).setTargetObjectIds(Arrays.asList(100));
    }

    @Test
    public void setActiveStep_itemIds_setItemOverlay() {
        OverlayData data = OverlayData.builder().targetItemIds(Arrays.asList(995)).build();
        controller.setActiveStep(PlannedStep.builder().overlayData(data).build());
        verify(item).setTargetItemIds(Arrays.asList(995));
    }

    @Test
    public void setActiveStep_pathPoints_setPath() {
        OverlayData data = OverlayData.builder().pathPoints(Arrays.asList(
            new WorldPoint(1, 1, 0), new WorldPoint(2, 2, 0)
        )).build();
        controller.setActiveStep(PlannedStep.builder().overlayData(data).build());
        verify(path).setPathPoints(anyList());
    }

    @Test
    public void setActiveStep_singlePathPoint_doesNotSetPath() {
        OverlayData data = OverlayData.builder().pathPoints(Collections.singletonList(new WorldPoint(1, 1, 0))).build();
        controller.setActiveStep(PlannedStep.builder().overlayData(data).build());
        verify(path, never()).setPathPoints(anyList());
    }

    @Test
    public void setActiveStep_widgetIds_setWidgets() {
        OverlayData data = OverlayData.builder().widgetIds(Arrays.asList(123)).build();
        controller.setActiveStep(PlannedStep.builder().overlayData(data).build());
        verify(widget).setTargetWidgetIds(Arrays.asList(123));
    }

    @Test
    public void setActiveStep_alwaysClearsFirst() {
        WorldPoint wp = new WorldPoint(1, 2, 0);
        OverlayData data = OverlayData.builder().targetTile(wp).build();
        controller.setActiveStep(PlannedStep.builder().overlayData(data).build());
        // Verify clear() was called on tile before setTargetTile
        var inOrder = inOrder(tile);
        inOrder.verify(tile).clear();
        inOrder.verify(tile).setTargetTile(wp);
    }
}
