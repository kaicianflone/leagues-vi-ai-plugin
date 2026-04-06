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
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class PathOverlayTest {

    private Client client;
    private LeaguesAiConfig config;
    private PathOverlay overlay;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = mock(LeaguesAiConfig.class);
        when(config.overlayColor()).thenReturn(Color.BLUE);
        overlay = new PathOverlay(client, config);
    }

    @Test
    public void render_withNoTarget_doesNotTouchGraphics() {
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void render_withSinglePoint_doesNotDraw() {
        overlay.setPathPoints(Collections.singletonList(new WorldPoint(1, 2, 0)));
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void setAndClear_pathPoints() {
        overlay.setPathPoints(Arrays.asList(new WorldPoint(1, 1, 0), new WorldPoint(2, 2, 0)));
        assertEquals(2, overlay.getPathPoints().size());
        overlay.clear();
        assertNull(overlay.getPathPoints());
    }

    @Test
    public void render_whenLocalPointNull_doesNotDrawLine() {
        overlay.setPathPoints(Arrays.asList(new WorldPoint(1, 1, 0), new WorldPoint(2, 2, 0)));
        Graphics2D g = mock(Graphics2D.class);
        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class)) {
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), any(WorldPoint.class))).thenReturn(null);
            assertNull(overlay.render(g));
        }
        verify(g, never()).drawLine(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void render_whenPathInScene_drawsLines() {
        overlay.setPathPoints(Arrays.asList(
            new WorldPoint(1, 1, 0),
            new WorldPoint(2, 2, 0),
            new WorldPoint(3, 3, 0)
        ));
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = spy(img.createGraphics());

        LocalPoint lp = new LocalPoint(100, 100);
        Polygon poly = new Polygon(new int[]{0, 10, 10, 0}, new int[]{0, 0, 10, 10}, 4);

        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class);
             MockedStatic<Perspective> perspMock = mockStatic(Perspective.class)) {
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), any(WorldPoint.class))).thenReturn(lp);
            perspMock.when(() -> Perspective.getCanvasTilePoly(eq(client), eq(lp))).thenReturn(poly);

            assertNull(overlay.render(g));

            // 3 points = 2 line segments
            verify(g, times(2)).drawLine(anyInt(), anyInt(), anyInt(), anyInt());
        }
        g.dispose();
    }
}
