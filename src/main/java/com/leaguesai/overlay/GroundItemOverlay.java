package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Highlights tiles containing ground items matching a target ID set. Uses an
 * event-driven cache (ItemSpawned/ItemDespawned) so the render loop is
 * O(matching tiles) instead of O(scene tiles).
 */
public class GroundItemOverlay extends Overlay {

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private volatile Set<Integer> targetItemIds = Collections.emptySet();

    // Concurrent map: tile (by WorldPoint) -> count of matching items currently
    // on that tile. We track counts so a despawn only removes a tile from the
    // overlay set when no matching items remain.
    private final ConcurrentHashMap<WorldPoint, Integer> matchingTiles = new ConcurrentHashMap<>();

    @Inject
    public GroundItemOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setTargetItemIds(List<Integer> ids) {
        this.targetItemIds = ids != null && !ids.isEmpty()
                ? new HashSet<>(ids)
                : Collections.emptySet();
        rebuildCacheFromScene();
    }

    public void clear() {
        this.targetItemIds = Collections.emptySet();
        matchingTiles.clear();
    }

    private void rebuildCacheFromScene() {
        matchingTiles.clear();
        if (targetItemIds.isEmpty()) {
            return;
        }
        Scene scene = client.getScene();
        if (scene == null) {
            return;
        }
        Tile[][][] tiles = scene.getTiles();
        if (tiles == null) {
            return;
        }
        for (int plane = 0; plane < tiles.length; plane++) {
            Tile[][] planeTiles = tiles[plane];
            if (planeTiles == null) continue;
            for (int x = 0; x < planeTiles.length; x++) {
                Tile[] row = planeTiles[x];
                if (row == null) continue;
                for (int y = 0; y < row.length; y++) {
                    Tile tile = row[y];
                    if (tile == null) continue;
                    List<TileItem> groundItems = tile.getGroundItems();
                    if (groundItems == null || groundItems.isEmpty()) continue;
                    for (TileItem item : groundItems) {
                        if (item != null && targetItemIds.contains(item.getId())) {
                            WorldPoint wp = tile.getWorldLocation();
                            if (wp != null) {
                                matchingTiles.merge(wp, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }
    }

    @Subscribe
    public void onItemSpawned(ItemSpawned event) {
        TileItem item = event.getItem();
        Tile tile = event.getTile();
        if (item == null || tile == null) return;
        if (!targetItemIds.contains(item.getId())) return;
        WorldPoint wp = tile.getWorldLocation();
        if (wp != null) {
            matchingTiles.merge(wp, 1, Integer::sum);
        }
    }

    @Subscribe
    public void onItemDespawned(ItemDespawned event) {
        TileItem item = event.getItem();
        Tile tile = event.getTile();
        if (item == null || tile == null) return;
        if (!targetItemIds.contains(item.getId())) return;
        WorldPoint wp = tile.getWorldLocation();
        if (wp == null) return;
        matchingTiles.computeIfPresent(wp, (k, v) -> v <= 1 ? null : v - 1);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (matchingTiles.isEmpty()) {
            return null;
        }
        Color color = config.overlayColor();
        for (WorldPoint wp : matchingTiles.keySet()) {
            LocalPoint lp = LocalPoint.fromWorld(client, wp);
            if (lp == null) continue;
            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;
            OverlayUtil.renderPolygon(graphics, poly, color);
        }
        return null;
    }

    // Test helper: package-private accessor for the cached match set.
    Set<WorldPoint> getMatchingTilesForTest() {
        return matchingTiles.keySet();
    }
}
