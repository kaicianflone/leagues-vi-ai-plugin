package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.util.Collections;
import java.util.List;

@Singleton
public class NpcHighlightOverlay extends Overlay {

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private List<Integer> targetNpcIds;

    @Inject
    public NpcHighlightOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setTargetNpcIds(List<Integer> ids) {
        this.targetNpcIds = ids;
    }

    public void clear() {
        this.targetNpcIds = null;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (targetNpcIds == null || targetNpcIds.isEmpty()) {
            return null;
        }
        List<NPC> npcs = client.getNpcs();
        if (npcs == null || npcs.isEmpty()) {
            return null;
        }
        for (NPC npc : npcs) {
            if (npc == null || !targetNpcIds.contains(npc.getId())) {
                continue;
            }
            Shape hull = npc.getConvexHull();
            if (hull == null) {
                continue;
            }
            OverlayUtil.renderPolygon(graphics, hull, config.overlayColor());
        }
        return null;
    }
}
