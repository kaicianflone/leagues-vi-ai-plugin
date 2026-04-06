package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;

public class MinimapOverlay extends Overlay {

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private WorldPoint targetPoint;

    @Inject
    public MinimapOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
    }

    public void setTargetPoint(WorldPoint targetPoint) {
        this.targetPoint = targetPoint;
    }

    public void clear() {
        this.targetPoint = null;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (targetPoint == null) {
            return null;
        }
        LocalPoint lp = LocalPoint.fromWorld(client, targetPoint);
        if (lp == null) {
            return null;
        }
        Point mp = Perspective.localToMinimap(client, lp);
        if (mp == null) {
            return null;
        }
        OverlayUtil.renderMinimapLocation(graphics, mp, config.overlayColor());
        return null;
    }
}
