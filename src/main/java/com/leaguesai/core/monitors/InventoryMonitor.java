package com.leaguesai.core.monitors;

import com.leaguesai.core.events.InventoryStateEvent;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class InventoryMonitor {

    private final EventBus eventBus;

    @Inject
    public InventoryMonitor(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != InventoryID.INVENTORY.getId()) {
            return;
        }

        ItemContainer container = event.getItemContainer();
        Map<Integer, Integer> items = new HashMap<>();

        if (container != null) {
            for (Item item : container.getItems()) {
                if (item.getId() == -1) {
                    continue;
                }
                items.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
        }

        eventBus.post(new InventoryStateEvent(items));
    }
}
