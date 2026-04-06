package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

public class WidgetOverlayTest {

    private Client client;
    private LeaguesAiConfig config;
    private WidgetOverlay overlay;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = mock(LeaguesAiConfig.class);
        when(config.overlayColor()).thenReturn(Color.WHITE);
        overlay = new WidgetOverlay(client, config);
    }

    @Test
    public void render_withNoTarget_doesNotTouchGraphics() {
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void setAndClear_widgetIds() {
        overlay.setTargetWidgetIds(Arrays.asList(0x000A_000B));
        assertEquals(Arrays.asList(0x000A_000B), overlay.getTargetWidgetIds());
        overlay.clear();
        assertNull(overlay.getTargetWidgetIds());
    }

    @Test
    public void render_whenWidgetNull_doesNotDraw() {
        overlay.setTargetWidgetIds(Collections.singletonList((10 << 16) | 5));
        when(client.getWidget(10, 5)).thenReturn(null);
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verify(g, never()).fillRect(anyInt(), anyInt(), anyInt(), anyInt());
        verify(g, never()).drawRect(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void render_whenWidgetHidden_doesNotDraw() {
        Widget w = mock(Widget.class);
        when(w.isHidden()).thenReturn(true);
        when(client.getWidget(10, 5)).thenReturn(w);
        overlay.setTargetWidgetIds(Collections.singletonList((10 << 16) | 5));
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verify(g, never()).fillRect(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void render_whenWidgetVisible_drawsRect() {
        Widget w = mock(Widget.class);
        when(w.isHidden()).thenReturn(false);
        when(w.getBounds()).thenReturn(new Rectangle(10, 20, 100, 50));
        when(client.getWidget(10, 5)).thenReturn(w);

        overlay.setTargetWidgetIds(Collections.singletonList((10 << 16) | 5));

        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = spy(img.createGraphics());
        assertNull(overlay.render(g));

        verify(g, times(1)).fillRect(10, 20, 100, 50);
        verify(g, times(1)).drawRect(10, 20, 100, 50);
        g.dispose();
    }
}
