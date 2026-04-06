package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class MinimapOverlayTest {

    private Client client;
    private LeaguesAiConfig config;
    private Player player;
    private MinimapOverlay overlay;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = mock(LeaguesAiConfig.class);
        player = mock(Player.class);
        when(config.overlayColor()).thenReturn(Color.RED);
        when(client.getLocalPlayer()).thenReturn(player);
        // Minimap zoom of 4.0 -> getMaxMinimapDrawDistance == 16 tiles.
        when(client.getMinimapZoom()).thenReturn(4.0);
        // Default player at a known world position.
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
        when(player.getLocalLocation()).thenReturn(new LocalPoint(6400, 6400));
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
    public void render_noLocalPlayer_doesNotDraw() {
        overlay.setTargetPoint(new WorldPoint(3201, 3201, 0));
        when(client.getLocalPlayer()).thenReturn(null);
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void render_whenTargetInScene_drawsShortPin() {
        // Target 5 tiles away, same plane -> distance (5) < max (16) -> "pin" branch.
        WorldPoint target = new WorldPoint(3205, 3205, 0);
        overlay.setTargetPoint(target);

        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = spy(img.createGraphics());

        LocalPoint targetLp = new LocalPoint(6500, 6500);
        Point mp = new Point(150, 80);

        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class);
             MockedStatic<Perspective> perspMock = mockStatic(Perspective.class)) {
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), eq(target))).thenReturn(targetLp);
            perspMock.when(() -> Perspective.localToMinimap(eq(client), eq(targetLp))).thenReturn(mp);

            assertNull(overlay.render(g));

            // Ported DirectionArrow.drawArrow flow: setColor(BLACK), setStroke, draw,
            // then setColor(color), setStroke, draw.
            verify(g, atLeastOnce()).setColor(eq(Color.BLACK));
            verify(g, atLeastOnce()).setColor(eq(Color.RED));
            verify(g, atLeastOnce()).setStroke(any(BasicStroke.class));
            verify(g, atLeastOnce()).draw(any(Shape.class));
        }
        g.dispose();
    }

    @Test
    public void render_whenTargetTooFarAndMinimapNull_takesOffSceneBranchGracefully() {
        // Far target (> 16 tiles) triggers createMinimapDirectionArrow. With
        // player.getMinimapLocation() stubbed to null, the off-scene path exits
        // without drawing anything.
        WorldPoint farTarget = new WorldPoint(2757, 3179, 0);
        overlay.setTargetPoint(farTarget);
        when(player.getMinimapLocation()).thenReturn(null);

        Graphics2D g = mock(Graphics2D.class);

        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class)) {
            // Whether the LocalPoint resolves or not is irrelevant; distance alone
            // pushes us off the pin branch.
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), any(WorldPoint.class))).thenReturn(null);

            assertNull(overlay.render(g));
        }

        verify(g, never()).draw(any(Shape.class));
        verify(g, never()).fill(any(Shape.class));
    }

    @Test
    public void render_whenDifferentPlane_takesOffSceneBranch() {
        // Cross-plane targets bypass the "pin" branch entirely. With a null
        // minimap location the off-scene branch exits without drawing.
        overlay.setTargetPoint(new WorldPoint(3201, 3201, 1));
        when(player.getMinimapLocation()).thenReturn(null);

        Graphics2D g = mock(Graphics2D.class);
        try (MockedStatic<LocalPoint> lpMock = mockStatic(LocalPoint.class)) {
            lpMock.when(() -> LocalPoint.fromWorld(eq(client), any(WorldPoint.class))).thenReturn(null);
            assertNull(overlay.render(g));
        }
        verify(g, never()).draw(any(Shape.class));
    }
}
