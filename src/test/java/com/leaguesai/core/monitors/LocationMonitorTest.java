package com.leaguesai.core.monitors;

import com.leaguesai.core.events.LocationChangedEvent;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class LocationMonitorTest {

    @Mock
    private Client client;

    @Mock
    private Player player;

    @Mock
    private EventBus eventBus;

    private LocationMonitor locationMonitor;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        locationMonitor = new LocationMonitor(client, eventBus);
        when(client.getLocalPlayer()).thenReturn(player);
    }

    @Test
    public void testFiresEventOnRegionChange() {
        // First tick: lastRegionId starts at -1, so any real region should fire
        WorldPoint worldPoint = new WorldPoint(3200, 3200, 0);
        when(player.getWorldLocation()).thenReturn(worldPoint);

        locationMonitor.onGameTick(new GameTick());

        ArgumentCaptor<LocationChangedEvent> captor = ArgumentCaptor.forClass(LocationChangedEvent.class);
        verify(eventBus, times(1)).post(captor.capture());

        LocationChangedEvent fired = captor.getValue();
        assertEquals(worldPoint, fired.getWorldPoint());
        assertEquals(worldPoint.getRegionID(), fired.getRegionId());
    }

    @Test
    public void testNoEventWhenSameRegion() {
        // Both ticks use the same world point / same region
        WorldPoint worldPoint = new WorldPoint(3200, 3200, 0);
        when(player.getWorldLocation()).thenReturn(worldPoint);

        // First tick fires the event (region changed from -1)
        locationMonitor.onGameTick(new GameTick());
        // Second tick same location — no new event
        locationMonitor.onGameTick(new GameTick());

        // Event should only have been posted once
        verify(eventBus, times(1)).post(any(LocationChangedEvent.class));
    }
}
