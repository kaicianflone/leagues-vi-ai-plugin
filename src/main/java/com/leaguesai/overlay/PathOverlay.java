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
 * Copyright (c) 2018, Lotto <https://github.com/devLotto>
 * Copyright (c) 2019, Trevor <https://github.com/Trevor159>
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
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.util.List;

/**
 * Path line overlay ported from Quest Helper's
 * {@code com.questhelper.steps.overlay.WorldLines.drawLinesOnWorld}
 * (lines 161-267) and {@code WorldLines.getWorldLines} (lines 130-159).
 *
 * <p>Each pair of consecutive waypoints is projected from world space to
 * canvas space using tile-accurate heights via {@link Perspective#getTileHeight}
 * and rendered via {@link OverlayUtil#renderPolygon(Graphics2D, java.awt.Shape, Color)}.
 */
@Singleton
public class PathOverlay extends Overlay {

    private static final int MAX_LP = 13056;
    private static final WorldPoint DONT_RENDER_POINT = new WorldPoint(0, 0, 0);

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private List<WorldPoint> pathPoints;

    @Inject
    public PathOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setPathPoints(List<WorldPoint> pathPoints) {
        this.pathPoints = pathPoints;
    }

    public void clear() {
        this.pathPoints = null;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (pathPoints == null || pathPoints.size() < 2) {
            return null;
        }
        drawLinesOnWorld(graphics, client, pathPoints, config.overlayColor());
        return null;
    }

    // ---------------------------------------------------------------------
    // Ported from WorldLines.java
    // ---------------------------------------------------------------------

    /**
     * Near-verbatim port of {@code WorldLines.drawLinesOnWorld} (lines 161-267).
     * Walks the path, resolving each {@link WorldPoint} pair to in-scene
     * {@link LocalPoint}s. When one endpoint is off-scene the intersection
     * with the loaded-scene border is computed so the line still renders from
     * the visible edge.
     */
    static void drawLinesOnWorld(Graphics2D graphics, Client client,
                                 List<WorldPoint> linePoints, Color color) {
        for (int i = 0; i < linePoints.size() - 1; i++) {
            WorldPoint startWp = linePoints.get(i);
            WorldPoint endWp = linePoints.get(i + 1);

            if (startWp == null || endWp == null) continue;
            if (startWp.equals(DONT_RENDER_POINT)) continue;
            if (endWp.equals(DONT_RENDER_POINT)) continue;
            if (startWp.getPlane() != endWp.getPlane()) continue;

            LocalPoint startPoint = LocalPoint.fromWorld(client, startWp);
            LocalPoint destinationPoint = LocalPoint.fromWorld(client, endWp);

            if (destinationPoint == null && startPoint == null) {
                continue;
            }

            // If the destination falls outside the loaded scene, find the
            // LocalPoint where the line intersects the scene border, so we
            // can still render the portion of the segment that is visible.
            // WorldLines.java lines 181-217.
            if (destinationPoint == null) {
                int xDiff = endWp.getX() - startWp.getX();
                int yDiff = endWp.getY() - startWp.getY();

                int changeToGetXToBorder;
                if (xDiff != 0) {
                    int goalLine = 0;
                    if (xDiff > 0) goalLine = MAX_LP;
                    changeToGetXToBorder = (goalLine - startPoint.getX()) / xDiff;
                } else {
                    changeToGetXToBorder = Integer.MAX_VALUE;
                }
                int changeToGetYToBorder;
                if (yDiff != 0) {
                    int goalLine = 0;
                    if (yDiff > 0) goalLine = MAX_LP;
                    changeToGetYToBorder = (goalLine - startPoint.getY()) / yDiff;
                } else {
                    changeToGetYToBorder = Integer.MAX_VALUE;
                }
                if (Math.abs(changeToGetXToBorder) < Math.abs(changeToGetYToBorder)) {
                    destinationPoint = new LocalPoint(
                            startPoint.getX() + (xDiff * changeToGetXToBorder),
                            startPoint.getY() + (yDiff * changeToGetXToBorder));
                } else {
                    destinationPoint = new LocalPoint(
                            startPoint.getX() + (xDiff * changeToGetYToBorder),
                            startPoint.getY() + (yDiff * changeToGetYToBorder));
                }
            }

            // Mirror case: the start is off-scene but the destination is in-scene.
            // WorldLines.java lines 219-257.
            if (startPoint == null) {
                int xDiff = startWp.getX() - endWp.getX();
                int yDiff = startWp.getY() - endWp.getY();

                int changeToGetXToBorder;
                if (xDiff != 0) {
                    int goalLine = 0;
                    if (xDiff > 0) goalLine = MAX_LP;
                    changeToGetXToBorder = (goalLine - destinationPoint.getX()) / xDiff;
                } else {
                    changeToGetXToBorder = 1000000000;
                }
                int changeToGetYToBorder;
                if (yDiff != 0) {
                    int goalLine = 0;
                    if (yDiff > 0) goalLine = MAX_LP;
                    changeToGetYToBorder = (goalLine - destinationPoint.getY()) / yDiff;
                } else {
                    changeToGetYToBorder = 1000000000;
                }
                if (Math.abs(changeToGetXToBorder) < Math.abs(changeToGetYToBorder)) {
                    startPoint = new LocalPoint(
                            destinationPoint.getX() + (xDiff * changeToGetXToBorder),
                            destinationPoint.getY() + (yDiff * changeToGetXToBorder));
                } else {
                    startPoint = new LocalPoint(
                            destinationPoint.getX() + (xDiff * changeToGetYToBorder),
                            destinationPoint.getY() + (yDiff * changeToGetYToBorder));
                }
            }

            Line2D.Double newLine = getWorldLines(client, startPoint, destinationPoint);
            if (newLine != null) {
                OverlayUtil.renderPolygon(graphics, newLine, color);
            }
        }
    }

    /**
     * Near-verbatim port of {@code WorldLines.getWorldLines} (lines 130-159).
     */
    static Line2D.Double getWorldLines(Client client, LocalPoint startLocation,
                                       LocalPoint endLocation) {
        if (startLocation == null || endLocation == null) {
            return null;
        }
        final int plane = client.getPlane();

        final int startX = startLocation.getX();
        final int startY = startLocation.getY();
        final int endX = endLocation.getX();
        final int endY = endLocation.getY();

        final int sceneX = startLocation.getSceneX();
        final int sceneY = startLocation.getSceneY();

        if (sceneX < 0 || sceneY < 0 || sceneX >= Constants.SCENE_SIZE || sceneY >= Constants.SCENE_SIZE) {
            return null;
        }

        final int startHeight = Perspective.getTileHeight(client, startLocation, plane);
        final int endHeight = Perspective.getTileHeight(client, endLocation, plane);

        Point p1 = Perspective.localToCanvas(client, startX, startY, startHeight);
        Point p2 = Perspective.localToCanvas(client, endX, endY, endHeight);

        if (p1 == null || p2 == null) {
            return null;
        }

        return new Line2D.Double(p1.getX(), p1.getY(), p2.getX(), p2.getY());
    }
}
