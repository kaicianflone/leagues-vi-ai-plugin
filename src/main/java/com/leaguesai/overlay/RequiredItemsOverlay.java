package com.leaguesai.overlay;

import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quest Helper-style required items panel. Shows the items a task requires
 * alongside a check/cross based on whether the player currently has them
 * in their inventory (or equipment).
 *
 * <p>Hidden when no required items are set.</p>
 */
@Singleton
public class RequiredItemsOverlay extends OverlayPanel {

    private static final Color HAVE_COLOR = new Color(120, 220, 120);
    private static final Color MISSING_COLOR = new Color(240, 100, 100);

    private final Client client;
    private final ItemManager itemManager;

    @Getter
    private Map<String, Integer> requiredItems = Collections.emptyMap();

    @Inject
    public RequiredItemsOverlay(Client client, ItemManager itemManager) {
        this.client = client;
        this.itemManager = itemManager;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        getPanelComponent().setPreferredSize(new Dimension(180, 0));
    }

    /**
     * Set the required items for the current step. Pass null or empty to hide.
     * Keys are display names, values are required quantities.
     */
    public void setRequiredItems(Map<String, Integer> items) {
        this.requiredItems = items != null
                ? new LinkedHashMap<>(items)
                : Collections.emptyMap();
    }

    public void clear() {
        this.requiredItems = Collections.emptyMap();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (requiredItems.isEmpty()) {
            return null;
        }

        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Required Items")
                .color(Color.WHITE)
                .build());

        // Build a lowercase name → quantity map of what the player has
        Map<String, Integer> have = collectInventoryNames();

        for (Map.Entry<String, Integer> entry : requiredItems.entrySet()) {
            String name = entry.getKey();
            int required = entry.getValue();
            int owned = have.getOrDefault(name.toLowerCase(), 0);
            boolean has = owned >= required;
            String rightText = has
                    ? "\u2713"
                    : (owned + "/" + required);
            Color color = has ? HAVE_COLOR : MISSING_COLOR;
            panelComponent.getChildren().add(LineComponent.builder()
                    .left(required > 1 ? name + " x" + required : name)
                    .right(rightText)
                    .leftColor(color)
                    .rightColor(color)
                    .build());
        }

        return super.render(graphics);
    }

    /**
     * Collects a lowercase-keyed inventory snapshot: item display name → total quantity.
     * Uses ItemManager to resolve item IDs to names. Safe against null containers.
     */
    private Map<String, Integer> collectInventoryNames() {
        Map<String, Integer> result = new LinkedHashMap<>();
        addContainer(result, client.getItemContainer(InventoryID.INVENTORY));
        addContainer(result, client.getItemContainer(InventoryID.EQUIPMENT));
        return result;
    }

    private void addContainer(Map<String, Integer> target, ItemContainer container) {
        if (container == null) return;
        for (Item item : container.getItems()) {
            if (item == null || item.getId() == -1) continue;
            try {
                String name = itemManager.getItemComposition(item.getId()).getName();
                if (name == null || name.isEmpty()) continue;
                target.merge(name.toLowerCase(), item.getQuantity(), Integer::sum);
            } catch (Exception ignored) {
                // Item cache miss — skip silently
            }
        }
    }
}
