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

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;

public class ArrowOverlay extends Overlay {

    private static final int Z_OFFSET = 200;
    private static final int ARROW_HALF_WIDTH = 12;
    private static final int ARROW_HEIGHT = 20;

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private WorldPoint targetTile;

    @Inject
    public ArrowOverlay(Client client, LeaguesAiConfig config) {
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
        Polygon tilePoly = Perspective.getCanvasTilePoly(client, lp);
        if (tilePoly == null) {
            return null;
        }
        Rectangle bounds = tilePoly.getBounds();
        int cx = (int) bounds.getCenterX();
        int cy = (int) bounds.getCenterY() - Z_OFFSET;

        // Downward-pointing triangle above tile
        Polygon arrow = new Polygon(
            new int[] { cx - ARROW_HALF_WIDTH, cx + ARROW_HALF_WIDTH, cx },
            new int[] { cy, cy, cy + ARROW_HEIGHT },
            3
        );
        graphics.setColor(config.overlayColor());
        graphics.fillPolygon(arrow);
        graphics.drawPolygon(arrow);
        return null;
    }
}
