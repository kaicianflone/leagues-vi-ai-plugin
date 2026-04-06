package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

import javax.inject.Inject;
import java.awt.image.BufferedImage;

/**
 * Manages a single world-map marker via WorldMapPointManager.
 * Does not extend Overlay — world map markers are managed by the manager.
 */
public class WorldMapOverlay {

    private final Client client;
    private final LeaguesAiConfig config;
    private final WorldMapPointManager manager;

    @Getter
    private WorldMapPoint currentPoint;

    @Getter
    private WorldPoint targetPoint;

    @Inject
    public WorldMapOverlay(Client client, LeaguesAiConfig config, WorldMapPointManager manager) {
        this.client = client;
        this.config = config;
        this.manager = manager;
    }

    /** Test/no-manager constructor: world-map updates become no-ops. */
    public WorldMapOverlay(Client client, LeaguesAiConfig config) {
        this(client, config, null);
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
