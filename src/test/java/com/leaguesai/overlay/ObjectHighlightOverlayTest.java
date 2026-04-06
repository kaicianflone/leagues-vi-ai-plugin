package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import org.junit.Before;
import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ObjectHighlightOverlayTest {

    private Client client;
    private LeaguesAiConfig config;
    private ObjectHighlightOverlay overlay;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = mock(LeaguesAiConfig.class);
        when(config.overlayColor()).thenReturn(Color.MAGENTA);
        overlay = new ObjectHighlightOverlay(client, config);
    }

    @Test
    public void render_withNoTarget_doesNotTouchGraphics() {
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void setAndClear_objectIds() {
        overlay.setTargetObjectIds(Arrays.asList(100, 200));
        assertEquals(Arrays.asList(100, 200), overlay.getTargetObjectIds());
        overlay.clear();
        assertNull(overlay.getTargetObjectIds());
    }

    @Test
    public void render_whenSceneNull_doesNotDraw() {
        overlay.setTargetObjectIds(Arrays.asList(100));
        when(client.getScene()).thenReturn(null);
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void render_whenMatchingObjectInScene_drawsHull() {
        overlay.setTargetObjectIds(Arrays.asList(555));

        Scene scene = mock(Scene.class);
        Tile[][][] tiles = new Tile[1][104][104];
        Tile tile = mock(Tile.class);
        tiles[0][50][50] = tile;

        GameObject obj = mock(GameObject.class);
        when(obj.getId()).thenReturn(555);
        Polygon hull = new Polygon(new int[]{0, 10, 10, 0}, new int[]{0, 0, 10, 10}, 4);
        when(obj.getConvexHull()).thenReturn(hull);
        when(tile.getGameObjects()).thenReturn(new GameObject[]{obj});

        when(client.getScene()).thenReturn(scene);
        when(scene.getTiles()).thenReturn(tiles);
        when(client.getPlane()).thenReturn(0);

        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = spy(img.createGraphics());
        assertNull(overlay.render(g));

        verify(g, atLeastOnce()).fill(any(Shape.class));
        verify(g, atLeastOnce()).draw(any(Shape.class));
        g.dispose();
    }
}
