package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;

@Singleton
public class WidgetOverlay extends Overlay {

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private List<Integer> targetWidgetIds;

    @Inject
    public WidgetOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    public void setTargetWidgetIds(List<Integer> ids) {
        this.targetWidgetIds = ids;
    }

    public void clear() {
        this.targetWidgetIds = null;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (targetWidgetIds == null || targetWidgetIds.isEmpty()) {
            return null;
        }
        Color base = config.overlayColor();
        Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 60);
        for (Integer widgetId : targetWidgetIds) {
            if (widgetId == null) continue;
            int group = widgetId >> 16;
            int child = widgetId & 0xFFFF;
            Widget w = client.getWidget(group, child);
            if (w == null || w.isHidden()) continue;
            Rectangle bounds = w.getBounds();
            if (bounds == null) continue;
            graphics.setColor(fill);
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            graphics.setColor(base);
            graphics.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
        return null;
    }
}
