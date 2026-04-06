package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import org.junit.Before;
import org.junit.Test;

import java.awt.image.BufferedImage;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WorldMapOverlayTest {

    private Client client;
    private LeaguesAiConfig config;
    private WorldMapPointManager manager;
    private WorldMapOverlay overlay;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = mock(LeaguesAiConfig.class);
        manager = mock(WorldMapPointManager.class);
        overlay = new WorldMapOverlay(client, config, manager);
    }

    @Test
    public void update_withNullPoint_doesNothing() {
        overlay.update(null, null);
        verify(manager, never()).add(any(WorldMapPoint.class));
        assertNull(overlay.getCurrentPoint());
        assertNull(overlay.getTargetPoint());
    }

    @Test
    public void update_addsPointToManager() {
        WorldPoint wp = new WorldPoint(3200, 3200, 0);
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        overlay.update(wp, icon);
        verify(manager, times(1)).add(any(WorldMapPoint.class));
        assertEquals(wp, overlay.getTargetPoint());
        assertNotNull(overlay.getCurrentPoint());
    }

    @Test
    public void clear_removesPointFromManager() {
        WorldPoint wp = new WorldPoint(3200, 3200, 0);
        overlay.update(wp, null);
        WorldMapPoint added = overlay.getCurrentPoint();
        assertNotNull(added);
        overlay.clear();
        verify(manager, times(1)).remove(added);
        assertNull(overlay.getCurrentPoint());
        assertNull(overlay.getTargetPoint());
    }

    @Test
    public void update_replacesExistingPoint() {
        WorldPoint a = new WorldPoint(1, 1, 0);
        WorldPoint b = new WorldPoint(2, 2, 0);
        overlay.update(a, null);
        WorldMapPoint first = overlay.getCurrentPoint();
        overlay.update(b, null);
        verify(manager, times(1)).remove(first);
        verify(manager, times(2)).add(any(WorldMapPoint.class));
        assertEquals(b, overlay.getTargetPoint());
    }

    @Test
    public void noManagerConstructor_isNoOp() {
        WorldMapOverlay noMgr = new WorldMapOverlay(client, config);
        WorldPoint wp = new WorldPoint(1, 2, 0);
        noMgr.update(wp, null);
        // No exception, target tracked but no manager interaction
        assertEquals(wp, noMgr.getTargetPoint());
        assertNull(noMgr.getCurrentPoint());
        noMgr.clear();
        assertNull(noMgr.getTargetPoint());
    }
}
