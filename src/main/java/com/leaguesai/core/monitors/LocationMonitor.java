package com.leaguesai.core.monitors;

import com.leaguesai.core.events.LocationChangedEvent;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class LocationMonitor {

    private final Client client;
    private final EventBus eventBus;
    private int lastRegionId = -1;

    @Inject
    public LocationMonitor(Client client, EventBus eventBus) {
        this.client = client;
        this.eventBus = eventBus;
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        Player player = client.getLocalPlayer();
        if (player == null) {
            return;
        }

        WorldPoint worldPoint = player.getWorldLocation();
        if (worldPoint == null) {
            return;
        }

        int regionId = worldPoint.getRegionID();
        if (regionId != lastRegionId) {
            lastRegionId = regionId;
            eventBus.post(new LocationChangedEvent(worldPoint, regionId));
        }
    }

    public WorldPoint getCurrentLocation() {
        Player player = client.getLocalPlayer();
        if (player == null) {
            return null;
        }
        return player.getWorldLocation();
    }
}
