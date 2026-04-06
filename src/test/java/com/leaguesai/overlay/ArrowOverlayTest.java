package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ArrowOverlayTest {

    private Client client;
    private LeaguesAiConfig config;
    private ArrowOverlay overlay;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = mock(LeaguesAiConfig.class);
        when(config.overlayColor()).thenReturn(Color.YELLOW);
        overlay = new ArrowOverlay(client, config);
    }

    @Test
    public void render_withNoTarget_doesNotTouchGraphics() {
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void setAndClear_targetTile() {
        WorldPoint wp = new WorldPoint(1, 2, 0);
        overlay.setTargetTile(wp);
        assertEquals(wp, overlay.getTargetTile());
        overlay.clear();
        assertNull(overlay.getTargetTile());
    }

    @Test
    public void render_whenLocalPointNull_doesNotDraw() {
        overlay.setTargetTile(new WorldPoint(3200, 3200, 0));
        Graphics2D g = mock(Graphics2D.class);
        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class)) {
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), any(WorldPoint.class))).thenReturn(null);
            assertNull(overlay.render(g));
        }
        verifyNoInteractions(g);
    }

    @Test
    public void render_whenTargetInScene_drawsArrowPolygon() {
        overlay.setTargetTile(new WorldPoint(3200, 3200, 0));
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = spy(img.createGraphics());

        LocalPoint mockLp = new LocalPoint(100, 100);
        Polygon mockTilePoly = new Polygon(new int[]{100, 110, 110, 100}, new int[]{300, 300, 310, 310}, 4);

        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class);
             MockedStatic<Perspective> perspMock = mockStatic(Perspective.class)) {
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), any(WorldPoint.class))).thenReturn(mockLp);
            perspMock.when(() -> Perspective.getCanvasTilePoly(eq(client), eq(mockLp))).thenReturn(mockTilePoly);

            assertNull(overlay.render(g));

            verify(g, atLeastOnce()).fillPolygon(any(Polygon.class));
            verify(g, atLeastOnce()).drawPolygon(any(Polygon.class));
        }
        g.dispose();
    }
}
