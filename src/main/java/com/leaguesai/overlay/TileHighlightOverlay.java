package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;

@Singleton
public class TileHighlightOverlay extends Overlay {

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private WorldPoint targetTile;

    @Inject
    public TileHighlightOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setTargetTile(WorldPoint targetTile) {
        this.targetTile = targetTile;
    }

    public void clear() {
        this.targetTile = null;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (targetTile == null) {
            return null;
        }
        LocalPoint lp = LocalPoint.fromWorld(client, targetTile);
        if (lp == null) {
            return null;
        }
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) {
            return null;
        }
        OverlayUtil.renderPolygon(graphics, poly, config.overlayColor());
        return null;
    }
}
