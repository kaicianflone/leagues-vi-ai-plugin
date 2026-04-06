/*
 * Portions of this file are ported from Quest Helper by Zoinkwiz
 * https://github.com/Zoinkwiz/quest-helper
 * Licensed under BSD-2-Clause. See ATTRIBUTIONS.md for the full license text.
 *
 * Original Quest Helper sources (reproduced copyright headers):
 *
 * Copyright (c) 2021, Zoinkwiz <https://github.com/Zoinkwiz>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;

/**
 * Minimap overlay that mirrors Quest Helper's behavior.
 *
 * <p>In-scene targets get a short vertical "pin" arrow drawn just above the
 * tile's minimap projection. Off-scene targets get a direction arrow drawn on
 * the minimap ring, projected outward from the player using the camera yaw.
 *
 * <p>This is a near-verbatim port of
 * {@code com.questhelper.steps.overlay.DirectionArrow} and the
 * {@code QuestPerspective#getMinimapPoint} helper from Quest Helper. See
 * inline comments for line-level references.
 */
@Slf4j
@Singleton
public class MinimapOverlay extends Overlay {

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private WorldPoint targetPoint;
    // Debug throttling: log once per target change, not every frame
    private WorldPoint lastLoggedTarget;
    private String lastLoggedBranch;

    @Inject
    public MinimapOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        // Ported from QuestHelperMinimapOverlay.java (ctor):
        //   setPosition(DYNAMIC); setLayer(ALWAYS_ON_TOP); setPriority(HIGH);
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(OverlayPriority.HIGH);
    }

    public void setTargetPoint(WorldPoint targetPoint) {
        this.targetPoint = targetPoint;
        this.lastLoggedTarget = null;  // Force re-log on target change
    }

    public void clear() {
        this.targetPoint = null;
        this.lastLoggedTarget = null;
    }

    private void logOnce(String branch, String details) {
        if (!branch.equals(lastLoggedBranch) || targetPoint != lastLoggedTarget) {
            log.info("MINIMAP DEBUG target={} branch={} {}", targetPoint, branch, details);
            lastLoggedBranch = branch;
            lastLoggedTarget = targetPoint;
        }
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (targetPoint == null) {
            return null;
        }

        Player local = client.getLocalPlayer();
        if (local == null) {
            logOnce("no-local-player", "");
            return null;
        }
        WorldPoint playerWp = local.getWorldLocation();
        if (playerWp == null) {
            logOnce("no-player-wp", "");
            return null;
        }

        Color color = config.overlayColor();

        // Mirrors DirectionArrow.renderMinimapArrowFromLocal (lines 98-133):
        // try to resolve the target LocalPoint; if within the minimap's draw
        // distance AND on the same plane, draw the short "pin" line above the
        // tile. Otherwise fall through to createMinimapDirectionArrow.
        int maxMinimapDrawDistance = getMaxMinimapDrawDistance(client);
        LocalPoint targetLp = LocalPoint.fromWorld(client, targetPoint);

        boolean samePlane = playerWp.getPlane() == targetPoint.getPlane();
        double tileDistance = playerWp.distanceTo2D(targetPoint);

        if (targetLp != null && samePlane && tileDistance < maxMinimapDrawDistance) {
            Point posOnMinimap = Perspective.localToMinimap(client, targetLp);
            if (posOnMinimap != null) {
                // DirectionArrow.java lines 128-131:
                //   Line2D.Double line = new Line2D.Double(
                //       posOnMinimap.getX(), posOnMinimap.getY() - 18,
                //       posOnMinimap.getX(), posOnMinimap.getY() - 8);
                //   drawMinimapArrow(graphics, line, color);
                Line2D.Double line = new Line2D.Double(
                        posOnMinimap.getX(), posOnMinimap.getY() - 18,
                        posOnMinimap.getX(), posOnMinimap.getY() - 8);
                logOnce("in-scene-pin", "at " + posOnMinimap);
                drawMinimapArrow(graphics, line, color);
                return null;
            }
            logOnce("in-scene-but-off-minimap", "targetLp=" + targetLp);
        }

        // Off-scene (or cross-plane / too far): draw an arrow on the minimap
        // ring pointing toward the target. Ported from
        // DirectionArrow.createMinimapDirectionArrow (lines 165-194).
        createMinimapDirectionArrow(graphics, client, targetPoint, color);
        return null;
    }

    // ---------------------------------------------------------------------
    // Ported from Quest Helper's DirectionArrow.java
    // ---------------------------------------------------------------------

    /**
     * Ported from {@code DirectionArrow.getMaxMinimapDrawDistance} (lines 47-55).
     */
    private static int getMaxMinimapDrawDistance(Client client) {
        double minimapZoom = client.getMinimapZoom();
        if (minimapZoom > 0.0) {
            return (int) (64.0 / minimapZoom);
        }
        return 16;
    }

    /**
     * Ported from {@code DirectionArrow.createMinimapDirectionArrow}
     * (lines 165-194). Draws a short arrow on the minimap ring pointing from
     * the player toward {@code wp}.
     */
    private void createMinimapDirectionArrow(Graphics2D graphics, Client client,
                                             WorldPoint wp, Color color) {
        Player player = client.getLocalPlayer();
        if (player == null || wp == null) {
            return;
        }

        WorldPoint playerRealWp = player.getWorldLocation();
        if (playerRealWp == null) {
            logOnce("no-player-real-wp", "");
            return;
        }

        Point playerPosOnMinimap = player.getMinimapLocation();
        Point destinationPosOnMinimap = getMinimapPoint(client, playerRealWp, wp);

        if (playerPosOnMinimap == null || destinationPosOnMinimap == null) {
            logOnce("minimap-projection-null",
                    "player=" + playerPosOnMinimap + " dest=" + destinationPosOnMinimap);
            return;
        }

        Line2D.Double line = getLine(playerPosOnMinimap, destinationPosOnMinimap);
        logOnce("drawing-arrow",
                "player=" + playerPosOnMinimap + " dest=" + destinationPosOnMinimap);
        drawMinimapArrow(graphics, line, color);
    }

    /**
     * Ported from {@code DirectionArrow.getLine} (lines 196-210). Builds the
     * short arrow line segment that sits on the minimap ring at radius 55-65.
     */
    private static Line2D.Double getLine(Point playerPosOnMinimap, Point destinationPosOnMinimap) {
        double xDiff = playerPosOnMinimap.getX() - destinationPosOnMinimap.getX();
        double yDiff = destinationPosOnMinimap.getY() - playerPosOnMinimap.getY();
        double angle = Math.atan2(yDiff, xDiff);

        int startX = (int) (playerPosOnMinimap.getX() - (Math.cos(angle) * 55));
        int startY = (int) (playerPosOnMinimap.getY() + (Math.sin(angle) * 55));

        int endX = (int) (playerPosOnMinimap.getX() - (Math.cos(angle) * 65));
        int endY = (int) (playerPosOnMinimap.getY() + (Math.sin(angle) * 65));

        return new Line2D.Double(startX, startY, endX, endY);
    }

    /**
     * Ported from {@code DirectionArrow.drawMinimapArrow} (line 223-226).
     */
    static void drawMinimapArrow(Graphics2D graphics, Line2D.Double line, Color color) {
        drawArrow(graphics, line, color, 6, 2, 2);
    }

    /**
     * Ported from {@code DirectionArrow.drawArrow} (lines 228-241). Draws a
     * thick black outline then the colored fill on top, with arrowhead.
     */
    static void drawArrow(Graphics2D graphics, Line2D.Double line, Color color,
                          int width, int tipHeight, int tipWidth) {
        graphics.setColor(Color.BLACK);
        graphics.setStroke(new BasicStroke(width));
        graphics.draw(line);
        drawWorldArrowHead(graphics, line, tipHeight, tipWidth);

        graphics.setColor(color);
        graphics.setStroke(new BasicStroke(width - 3));
        graphics.draw(line);
        drawWorldArrowHead(graphics, line, tipHeight - 2, tipWidth - 2);
        graphics.setStroke(new BasicStroke(1));
    }

    /**
     * Ported from {@code DirectionArrow.drawWorldArrowHead} (lines 244-262).
     */
    static void drawWorldArrowHead(Graphics2D g2d, Line2D.Double line,
                                   int extraSizeHeight, int extraSizeWidth) {
        AffineTransform tx = new AffineTransform();

        Polygon arrowHead = new Polygon();
        arrowHead.addPoint(0, 6 + extraSizeHeight);
        arrowHead.addPoint(-6 - extraSizeWidth, -1 - extraSizeHeight);
        arrowHead.addPoint(6 + extraSizeWidth, -1 - extraSizeHeight);

        tx.setToIdentity();
        double angle = Math.atan2(line.y2 - line.y1, line.x2 - line.x1);
        tx.translate(line.x2, line.y2);
        tx.rotate((angle - Math.PI / 2d));

        Graphics2D g = (Graphics2D) g2d.create();
        g.setTransform(tx);
        g.fill(arrowHead);
        g.dispose();
    }

    /**
     * Ported from {@code QuestPerspective.getMinimapPoint} (lines 214-283 of
     * {@code QuestPerspective.java}). Projects a world-space destination onto
     * the minimap widget using the camera yaw, returning the canvas pixel
     * location on the minimap that corresponds to the destination direction
     * (clipped via the player's minimap radius by the caller).
     */
    private static Point getMinimapPoint(Client client, WorldPoint start, WorldPoint destination) {
        if (start == null || destination == null) {
            return null;
        }

        int x = (destination.getX() - start.getX());
        int y = (destination.getY() - start.getY());

        float maxDistance = Math.max(Math.abs(x), Math.abs(y));
        if (maxDistance == 0) {
            return null;
        }

        x = x * 100;
        y = y * 100;
        x /= maxDistance;
        y /= maxDistance;

        Widget minimapDrawWidget;
        if (client.isResized()) {
            if (client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1) {
                minimapDrawWidget = client.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP);
            } else {
                minimapDrawWidget = client.getWidget(InterfaceID.ToplevelOsrsStretch.MINIMAP);
            }
        } else {
            // Fixed classic layout. Quest Helper uses InterfaceID.Toplevel.MINIMAP
            // but that field does not exist in the RuneLite 1.12.23 API; the
            // pre-EoC layout widget covers the fixed-layout minimap here.
            minimapDrawWidget = client.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP);
        }

        if (minimapDrawWidget == null) {
            return null;
        }

        final int angle = client.getCameraYawTarget() & 0x7FF;
        final int sin = Perspective.SINE[angle];
        final int cos = Perspective.COSINE[angle];

        final int xx = y * sin + cos * x >> 16;
        final int yy = sin * x - y * cos >> 16;

        Point loc = minimapDrawWidget.getCanvasLocation();
        int miniMapX = loc.getX() + xx + minimapDrawWidget.getWidth() / 2;
        int miniMapY = minimapDrawWidget.getHeight() / 2 + loc.getY() + yy;
        return new Point(miniMapX, miniMapY);
    }
}
