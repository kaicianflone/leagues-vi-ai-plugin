package com.leaguesai.overlay;

import com.google.inject.Inject;
import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

import java.awt.image.BufferedImage;

/**
 * Manages a single world-map marker via WorldMapPointManager.
 * Does not extend Overlay — world map markers are managed by the manager.
 */
public class WorldMapOverlay {

    private final Client client;
    private final LeaguesAiConfig config;
    private WorldMapPointManager manager;

    @Getter
    private WorldMapPoint currentPoint;

    @Getter
    private WorldPoint targetPoint;

    @Inject
    public WorldMapOverlay(Client client, LeaguesAiConfig config) {
        this(client, config, null);
    }

    /** Test constructor: pass an explicit manager (or null for no-op mode). */
    public WorldMapOverlay(Client client, LeaguesAiConfig config, WorldMapPointManager manager) {
        this.client = client;
        this.config = config;
        this.manager = manager;
    }

    /**
     * Optional manager injection: if {@link WorldMapPointManager} is bound in
     * the Guice context (i.e. running inside RuneLite), Guice will call this
     * method. Otherwise the field stays null and world-map updates are no-ops.
     */
    @Inject(optional = true)
    public void setManager(WorldMapPointManager manager) {
        this.manager = manager;
    }

    public void update(WorldPoint point, BufferedImage icon) {
        clear();
        if (point == null) {
            return;
        }
        this.targetPoint = point;
        if (manager == null) {
            return;
        }
        WorldMapPoint wmp = WorldMapPoint.builder()
            .worldPoint(point)
            .image(icon)
            .name("Leagues AI Target")
            .build();
        this.currentPoint = wmp;
        manager.add(wmp);
    }

    public void clear() {
        if (manager != null && currentPoint != null) {
            manager.remove(currentPoint);
        }
        this.currentPoint = null;
        this.targetPoint = null;
    }
}
