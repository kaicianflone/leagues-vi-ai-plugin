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
import java.awt.image.BufferedImage;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class MinimapOverlayTest {

    private Client client;
    private LeaguesAiConfig config;
    private MinimapOverlay overlay;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = mock(LeaguesAiConfig.class);
        when(config.overlayColor()).thenReturn(Color.RED);
        overlay = new MinimapOverlay(client, config);
    }

    @Test
    public void render_withNoTarget_doesNotTouchGraphics() {
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void setAndClear_targetPoint() {
        WorldPoint wp = new WorldPoint(1, 2, 0);
        overlay.setTargetPoint(wp);
        assertEquals(wp, overlay.getTargetPoint());
        overlay.clear();
        assertNull(overlay.getTargetPoint());
    }

    @Test
    public void render_whenLocalPointNull_doesNotDraw() {
        overlay.setTargetPoint(new WorldPoint(1, 2, 0));
        Graphics2D g = mock(Graphics2D.class);
        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class)) {
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), any(WorldPoint.class))).thenReturn(null);
            assertNull(overlay.render(g));
        }
        verifyNoInteractions(g);
    }

    @Test
    public void render_whenTargetInScene_drawsMinimapMarker() {
        overlay.setTargetPoint(new WorldPoint(1, 2, 0));
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = spy(img.createGraphics());

        LocalPoint lp = new LocalPoint(100, 100);
        Point mp = new Point(50, 50);

        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class);
             MockedStatic<Perspective> perspMock = mockStatic(Perspective.class)) {
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), any(WorldPoint.class))).thenReturn(lp);
            perspMock.when(() -> Perspective.localToMinimap(eq(client), eq(lp))).thenReturn(mp);

            assertNull(overlay.render(g));

            // OverlayUtil.renderMinimapLocation calls graphics.fillOval / drawLine etc.
            // At minimum, the graphics object's color must be set.
            verify(g, atLeastOnce()).setColor(any(Color.class));
        }
        g.dispose();
    }
}
