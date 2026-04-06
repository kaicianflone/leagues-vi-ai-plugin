package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Highlights game objects matching a set of target IDs. Uses an event-driven
 * cache: spawn/despawn events maintain {@link #matchingObjects} so the render
 * loop is O(matches) instead of O(scene tiles).
 */
public class ObjectHighlightOverlay extends Overlay {

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private volatile Set<Integer> targetObjectIds = Collections.emptySet();

    // Cached list of game objects currently in the scene whose IDs match.
    // CopyOnWriteArrayList: safe for the render loop to iterate without locking
    // while spawn/despawn handlers mutate.
    private final List<GameObject> matchingObjects = new CopyOnWriteArrayList<>();

    @Inject
    public ObjectHighlightOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setTargetObjectIds(List<Integer> ids) {
        this.targetObjectIds = ids != null && !ids.isEmpty()
                ? new HashSet<>(ids)
                : Collections.emptySet();
        rebuildCacheFromScene();
    }

    public void clear() {
        this.targetObjectIds = Collections.emptySet();
        matchingObjects.clear();
    }

    /**
     * Walks the current scene once to populate the cache with any matching
     * objects. Called on setTargetObjectIds — not on every frame.
     */
    private void rebuildCacheFromScene() {
        matchingObjects.clear();
        if (targetObjectIds.isEmpty()) {
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
                    GameObject[] objects = tile.getGameObjects();
                    if (objects == null) continue;
                    for (GameObject obj : objects) {
                        if (obj != null && targetObjectIds.contains(obj.getId())) {
                            matchingObjects.add(obj);
                        }
                    }
                }
            }
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject obj = event.getGameObject();
        if (obj != null && targetObjectIds.contains(obj.getId())) {
            matchingObjects.add(obj);
        }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event) {
        GameObject obj = event.getGameObject();
        if (obj != null) {
            matchingObjects.remove(obj);
        }
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (matchingObjects.isEmpty()) {
            return null;
        }
        Color color = config.overlayColor();
        for (GameObject obj : matchingObjects) {
            if (obj == null) continue;
            Shape hull = obj.getConvexHull();
            if (hull == null) continue;
            OverlayUtil.renderPolygon(graphics, hull, color);
        }
        return null;
    }

    // Test helper: package-private accessor for the cached match list.
    List<GameObject> getMatchingObjectsForTest() {
        return matchingObjects;
    }
}
