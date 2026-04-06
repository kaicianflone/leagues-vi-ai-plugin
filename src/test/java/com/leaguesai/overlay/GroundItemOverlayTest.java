package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class GroundItemOverlayTest {

    private Client client;
    private LeaguesAiConfig config;
    private GroundItemOverlay overlay;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = mock(LeaguesAiConfig.class);
        when(config.overlayColor()).thenReturn(Color.ORANGE);
        when(client.getScene()).thenReturn(null);
        overlay = new GroundItemOverlay(client, config);
    }

    @Test
    public void render_withNoTarget_doesNotTouchGraphics() {
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void setAndClear_itemIds() {
        overlay.setTargetItemIds(Arrays.asList(995));
        assertEquals(new HashSet<>(Arrays.asList(995)), overlay.getTargetItemIds());
        overlay.clear();
        assertTrue(overlay.getTargetItemIds().isEmpty());
    }

    @Test
    public void render_whenCacheEmpty_doesNotDraw() {
        overlay.setTargetItemIds(Arrays.asList(995));
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void onItemSpawned_addsTileToCacheAndRenders() {
        overlay.setTargetItemIds(Arrays.asList(995));

        WorldPoint wp = new WorldPoint(3200, 3200, 0);
        Tile tile = mock(Tile.class);
        when(tile.getWorldLocation()).thenReturn(wp);
        TileItem item = mock(TileItem.class);
        when(item.getId()).thenReturn(995);

        ItemSpawned event = mock(ItemSpawned.class);
        when(event.getItem()).thenReturn(item);
        when(event.getTile()).thenReturn(tile);

        overlay.onItemSpawned(event);
        assertTrue(overlay.getMatchingTilesForTest().contains(wp));

        LocalPoint lp = new LocalPoint(100, 100);
        Polygon poly = new Polygon(new int[]{0, 10, 10, 0}, new int[]{0, 0, 10, 10}, 4);

        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = spy(img.createGraphics());

        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class);
             MockedStatic<Perspective> perspMock = mockStatic(Perspective.class)) {
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), eq(wp))).thenReturn(lp);
            perspMock.when(() -> Perspective.getCanvasTilePoly(eq(client), eq(lp))).thenReturn(poly);

            assertNull(overlay.render(g));
            verify(g, atLeastOnce()).fill(any(Shape.class));
            verify(g, atLeastOnce()).draw(any(Shape.class));
        }
        g.dispose();
    }

    @Test
    public void onItemSpawned_ignoresNonMatching() {
        overlay.setTargetItemIds(Arrays.asList(995));
        Tile tile = mock(Tile.class);
        when(tile.getWorldLocation()).thenReturn(new WorldPoint(1, 2, 0));
        TileItem item = mock(TileItem.class);
        when(item.getId()).thenReturn(123);

        ItemSpawned event = mock(ItemSpawned.class);
        when(event.getItem()).thenReturn(item);
        when(event.getTile()).thenReturn(tile);

        overlay.onItemSpawned(event);
        assertTrue(overlay.getMatchingTilesForTest().isEmpty());
    }

    @Test
    public void onItemDespawned_decrementsAndRemovesWhenZero() {
        overlay.setTargetItemIds(Arrays.asList(995));
        WorldPoint wp = new WorldPoint(1, 2, 0);
        Tile tile = mock(Tile.class);
        when(tile.getWorldLocation()).thenReturn(wp);
        TileItem item = mock(TileItem.class);
        when(item.getId()).thenReturn(995);

        ItemSpawned spawn = mock(ItemSpawned.class);
        when(spawn.getItem()).thenReturn(item);
        when(spawn.getTile()).thenReturn(tile);
        overlay.onItemSpawned(spawn);
        overlay.onItemSpawned(spawn);
        assertTrue(overlay.getMatchingTilesForTest().contains(wp));

        ItemDespawned despawn = mock(ItemDespawned.class);
        when(despawn.getItem()).thenReturn(item);
        when(despawn.getTile()).thenReturn(tile);
        overlay.onItemDespawned(despawn);
        // Still present (count == 1)
        assertTrue(overlay.getMatchingTilesForTest().contains(wp));

        overlay.onItemDespawned(despawn);
        // Now removed
        assertFalse(overlay.getMatchingTilesForTest().contains(wp));
    }
}
