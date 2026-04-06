package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
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
        // Quest Helper's getWorldLines calls client.getPlane(); keep it deterministic.
        when(client.getPlane()).thenReturn(0);
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
    public void render_whenBothEndpointsOffScene_drawsNothing() {
        overlay.setPathPoints(Arrays.asList(new WorldPoint(1, 1, 0), new WorldPoint(2, 2, 0)));
        Graphics2D g = mock(Graphics2D.class);
        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class)) {
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), any(WorldPoint.class))).thenReturn(null);
            assertNull(overlay.render(g));
        }
        verify(g, never()).draw(any(Shape.class));
    }

    @Test
    public void render_whenPathInScene_drawsLines() {
        overlay.setPathPoints(Arrays.asList(
            new WorldPoint(3200, 3200, 0),
            new WorldPoint(3201, 3201, 0),
            new WorldPoint(3202, 3202, 0)
        ));
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = spy(img.createGraphics());

        // Scene-local coordinates inside the 104-tile scene grid so getSceneX/Y pass.
        LocalPoint lp = new LocalPoint(6400, 6400);
        Point canvasP = new Point(400, 300);

        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class);
             MockedStatic<Perspective> perspMock = mockStatic(Perspective.class)) {
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), any(WorldPoint.class))).thenReturn(lp);
            perspMock.when(() -> Perspective.getTileHeight(eq(client), eq(lp), anyInt())).thenReturn(0);
            perspMock.when(() -> Perspective.localToCanvas(eq(client), anyInt(), anyInt(), anyInt()))
                    .thenReturn(canvasP);

            assertNull(overlay.render(g));

            // 3 points = 2 segments, each drawn via OverlayUtil.renderPolygon which
            // ultimately calls g.draw(Shape) on the Line2D.Double.
            verify(g, times(2)).draw(any(Shape.class));
        }
        g.dispose();
    }
}
