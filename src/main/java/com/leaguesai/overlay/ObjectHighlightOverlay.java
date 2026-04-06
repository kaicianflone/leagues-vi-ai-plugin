package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.util.List;

/**
 * Walks all tiles in the scene (104x104) and highlights game objects
 * matching the target IDs. Note: this is O(~10k tiles) per frame; acceptable
 * for the MVP but could be cached on object spawn/despawn for production use.
 */
public class ObjectHighlightOverlay extends Overlay {

    private static final int SCENE_SIZE = 104;

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private List<Integer> targetObjectIds;

    @Inject
    public ObjectHighlightOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setTargetObjectIds(List<Integer> ids) {
        this.targetObjectIds = ids;
    }

    public void clear() {
        this.targetObjectIds = null;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (targetObjectIds == null || targetObjectIds.isEmpty()) {
            return null;
        }
        Scene scene = client.getScene();
        if (scene == null) {
            return null;
        }
        Tile[][][] tiles = scene.getTiles();
        if (tiles == null) {
            return null;
        }
        int plane = client.getPlane();
        if (plane < 0 || plane >= tiles.length) {
            return null;
        }
        Tile[][] planeTiles = tiles[plane];
        for (int x = 0; x < SCENE_SIZE && x < planeTiles.length; x++) {
            Tile[] row = planeTiles[x];
            if (row == null) continue;
            for (int y = 0; y < SCENE_SIZE && y < row.length; y++) {
                Tile tile = row[y];
                if (tile == null) continue;
                GameObject[] objects = tile.getGameObjects();
                if (objects == null) continue;
                for (GameObject obj : objects) {
                    if (obj == null || !targetObjectIds.contains(obj.getId())) {
                        continue;
                    }
                    Shape hull = obj.getConvexHull();
                    if (hull == null) {
                        continue;
                    }
                    OverlayUtil.renderPolygon(graphics, hull, config.overlayColor());
                }
            }
        }
        return null;
    }
}
