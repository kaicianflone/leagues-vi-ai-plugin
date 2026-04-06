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
import java.util.List;

public class PathOverlay extends Overlay {

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private List<WorldPoint> pathPoints;

    @Inject
    public PathOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setPathPoints(List<WorldPoint> pathPoints) {
        this.pathPoints = pathPoints;
    }

    public void clear() {
        this.pathPoints = null;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (pathPoints == null || pathPoints.size() < 2) {
            return null;
        }
        graphics.setColor(config.overlayColor());
        for (int i = 0; i < pathPoints.size() - 1; i++) {
            WorldPoint a = pathPoints.get(i);
            WorldPoint b = pathPoints.get(i + 1);
            if (a == null || b == null) continue;
            LocalPoint la = LocalPoint.fromWorld(client, a);
            LocalPoint lb = LocalPoint.fromWorld(client, b);
            if (la == null || lb == null) continue;
            Polygon pa = Perspective.getCanvasTilePoly(client, la);
            Polygon pb = Perspective.getCanvasTilePoly(client, lb);
            if (pa == null || pb == null) continue;
            Rectangle ra = pa.getBounds();
            Rectangle rb = pb.getBounds();
            graphics.drawLine(
                (int) ra.getCenterX(), (int) ra.getCenterY(),
                (int) rb.getCenterX(), (int) rb.getCenterY()
            );
        }
        return null;
    }
}
