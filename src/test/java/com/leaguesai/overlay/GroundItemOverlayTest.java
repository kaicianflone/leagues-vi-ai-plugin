package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
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
        assertEquals(Arrays.asList(995), overlay.getTargetItemIds());
        overlay.clear();
        assertNull(overlay.getTargetItemIds());
    }

    @Test
    public void render_whenSceneNull_doesNotDraw() {
        overlay.setTargetItemIds(Arrays.asList(995));
        when(client.getScene()).thenReturn(null);
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void render_whenMatchingItemInScene_drawsTilePolygon() {
        overlay.setTargetItemIds(Arrays.asList(995));

        Scene scene = mock(Scene.class);
        Tile[][][] tiles = new Tile[1][104][104];
        Tile tile = mock(Tile.class);
        tiles[0][50][50] = tile;

        TileItem item = mock(TileItem.class);
        when(item.getId()).thenReturn(995);
        when(tile.getGroundItems()).thenReturn(Collections.singletonList(item));

        LocalPoint lp = new LocalPoint(100, 100);
        when(tile.getLocalLocation()).thenReturn(lp);

        when(client.getScene()).thenReturn(scene);
        when(scene.getTiles()).thenReturn(tiles);
        when(client.getPlane()).thenReturn(0);

        Polygon poly = new Polygon(new int[]{0, 10, 10, 0}, new int[]{0, 0, 10, 10}, 4);
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = spy(img.createGraphics());

        try (MockedStatic<Perspective> perspMock = mockStatic(Perspective.class)) {
            perspMock.when(() -> Perspective.getCanvasTilePoly(eq(client), eq(lp))).thenReturn(poly);
            assertNull(overlay.render(g));

            verify(g, atLeastOnce()).fill(any(Shape.class));
            verify(g, atLeastOnce()).draw(any(Shape.class));
        }
        g.dispose();
    }
}
