package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.List;

public class GroundItemOverlay extends Overlay {

    private static final int SCENE_SIZE = 104;

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private List<Integer> targetItemIds;

    @Inject
    public GroundItemOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setTargetItemIds(List<Integer> ids) {
        this.targetItemIds = ids;
    }

    public void clear() {
        this.targetItemIds = null;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (targetItemIds == null || targetItemIds.isEmpty()) {
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
                List<TileItem> groundItems = tile.getGroundItems();
                if (groundItems == null || groundItems.isEmpty()) continue;
                boolean hasMatch = false;
                for (TileItem item : groundItems) {
                    if (item != null && targetItemIds.contains(item.getId())) {
                        hasMatch = true;
                        break;
                    }
                }
                if (!hasMatch) continue;
                LocalPoint lp = tile.getLocalLocation();
                if (lp == null) continue;
                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if (poly == null) continue;
                OverlayUtil.renderPolygon(graphics, poly, config.overlayColor());
            }
        }
        return null;
    }
}
