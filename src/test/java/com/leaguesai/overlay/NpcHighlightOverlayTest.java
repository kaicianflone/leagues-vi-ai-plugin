package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import org.junit.Before;
import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class NpcHighlightOverlayTest {

    private Client client;
    private LeaguesAiConfig config;
    private NpcHighlightOverlay overlay;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = mock(LeaguesAiConfig.class);
        when(config.overlayColor()).thenReturn(Color.GREEN);
        overlay = new NpcHighlightOverlay(client, config);
    }

    @Test
    public void render_withNoTarget_doesNotTouchGraphics() {
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void setAndClear_npcIds() {
        overlay.setTargetNpcIds(Arrays.asList(1, 2, 3));
        assertEquals(Arrays.asList(1, 2, 3), overlay.getTargetNpcIds());
        overlay.clear();
        assertNull(overlay.getTargetNpcIds());
    }

    @Test
    public void render_whenNpcsEmpty_doesNotDraw() {
        overlay.setTargetNpcIds(Arrays.asList(1));
        when(client.getNpcs()).thenReturn(Collections.emptyList());
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void render_whenMatchingNpcInScene_drawsHull() {
        overlay.setTargetNpcIds(Arrays.asList(42));

        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(42);
        Polygon hull = new Polygon(new int[]{0, 10, 10, 0}, new int[]{0, 0, 10, 10}, 4);
        when(npc.getConvexHull()).thenReturn(hull);

        NPC nonMatch = mock(NPC.class);
        when(nonMatch.getId()).thenReturn(7);

        when(client.getNpcs()).thenReturn(Arrays.asList(npc, nonMatch));

        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = spy(img.createGraphics());
        assertNull(overlay.render(g));

        verify(g, atLeastOnce()).fill(any(Shape.class));
        verify(g, atLeastOnce()).draw(any(Shape.class));
        // Non-matching NPC should not have its hull queried
        verify(nonMatch, never()).getConvexHull();
        g.dispose();
    }
}
