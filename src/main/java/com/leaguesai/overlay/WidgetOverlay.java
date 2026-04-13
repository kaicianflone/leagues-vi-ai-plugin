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

    /**
     * Shop interface group ID. OSRS uses 300 for most shops; some specific shops
     * (e.g. the GE) use different groups but the standard NPC shops use 300.
     */
    private static final int SHOP_GROUP = 300;
    /** Child index of the shop item container within the shop interface. */
    private static final int SHOP_ITEMS_CHILD = 16;

    @Getter
    private List<Integer> targetWidgetIds;

    /**
     * OSRS game item IDs to highlight when the shop interface (group 300) is open.
     * Scanned dynamically at render time via the shop container's dynamic children.
     */
    @Getter
    private List<Integer> shopItemGameIds;

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

    public void setShopItemGameIds(List<Integer> ids) {
        this.shopItemGameIds = ids;
    }

    public void clear() {
        this.targetWidgetIds = null;
        this.shopItemGameIds = null;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        Color base = config.overlayColor();
        Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 60);

        // Explicit widget-ID highlights (existing behaviour).
        if (targetWidgetIds != null && !targetWidgetIds.isEmpty()) {
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
        }

        // Shop item highlights: scan the shop interface for matching item IDs.
        // Mirrors Quest Helper's shop item highlighting. Scans both dynamic children
        // (most NPC shops) and static children of the items container widget.
        if (shopItemGameIds != null && !shopItemGameIds.isEmpty()) {
            highlightShopItems(graphics, base, fill);
        }

        return null;
    }

    /**
     * Scans the OSRS shop interface (group 300, child 16) for items whose game ID
     * matches {@link #shopItemGameIds} and draws a highlight box around each match.
     *
     * <p>Checks both {@link Widget#getDynamicChildren()} (standard NPC shops) and
     * direct static children, because different shop interfaces use different layouts.
     */
    private void highlightShopItems(Graphics2D graphics, Color base, Color fill) {
        Widget shopContainer = client.getWidget(SHOP_GROUP, SHOP_ITEMS_CHILD);
        if (shopContainer == null || shopContainer.isHidden()) return;

        // Try dynamic children first (most NPC shops store items here).
        Widget[] dynamicItems = shopContainer.getDynamicChildren();
        if (dynamicItems != null) {
            for (Widget item : dynamicItems) {
                drawIfMatch(graphics, base, fill, item);
            }
        }

        // Also try direct static children as a fallback (some interfaces differ).
        Widget[] staticItems = shopContainer.getChildren();
        if (staticItems != null) {
            for (Widget item : staticItems) {
                drawIfMatch(graphics, base, fill, item);
            }
        }
    }

    private void drawIfMatch(Graphics2D graphics, Color base, Color fill, Widget item) {
        if (item == null || item.isHidden()) return;
        if (!shopItemGameIds.contains(item.getItemId())) return;
        Rectangle bounds = item.getBounds();
        if (bounds == null) return;
        graphics.setColor(fill);
        graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        graphics.setColor(base);
        graphics.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
    }
}
