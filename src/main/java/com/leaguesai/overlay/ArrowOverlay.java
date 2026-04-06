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
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;

/**
 * 3D world arrow, ported from Quest Helper.
 *
 * <p>The arrow hovers above the target tile and blinks on/off on a
 * game-tick cadence identical to Quest Helper's
 * {@code DetailedQuestStep.makeWorldArrowOverlayHint} (lines 347-371 of
 * {@code DetailedQuestStep.java}): {@code currentRender} counts up each
 * {@link GameTick} modulo {@code MAX_RENDER_SIZE}, and the arrow is only
 * drawn while {@code currentRender < MAX_RENDER_SIZE / 2}.
 *
 * <p>The arrow itself is drawn by the ported
 * {@code DirectionArrow.drawWorldArrow} / {@code drawArrow} /
 * {@code drawWorldArrowHead} helpers.
 */
@Singleton
public class ArrowOverlay extends Overlay {

    // DetailedQuestStep.java line 345 (approx): MAX_RENDER_SIZE = 6.
    private static final int MAX_RENDER_SIZE = 6;

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private WorldPoint targetTile;

    private int currentRender = 0;

    @Inject
    public ArrowOverlay(Client client, LeaguesAiConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setTargetTile(WorldPoint targetTile) {
        this.targetTile = targetTile;
    }

    public void clear() {
        this.targetTile = null;
    }

    /**
     * Ported from {@code DetailedQuestStep.onGameTick} (lines 347-352).
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        currentRender = (currentRender + 1) % MAX_RENDER_SIZE;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (targetTile == null) {
            return null;
        }
        if (client.getLocalPlayer() == null) {
            return null;
        }
        // Ported blink: render only during the first half of the cycle.
        // DetailedQuestStep.java lines 367-370.
        if (currentRender >= (MAX_RENDER_SIZE / 2)) {
            return null;
        }

        LocalPoint lp = LocalPoint.fromWorld(client, targetTile);
        if (lp == null) {
            return null;
        }

        // DetailedQuestStep.renderArrow (lines 392-417):
        //   Polygon poly = Perspective.getCanvasTilePoly(client, localPoint, 30);
        //   int startX = poly.getBounds().x + poly.getBounds().width / 2;
        //   int startY = poly.getBounds().y + poly.getBounds().height / 2;
        //   DirectionArrow.drawWorldArrow(graphics, color, startX, startY);
        Polygon poly = Perspective.getCanvasTilePoly(client, lp, 30);
        if (poly == null || poly.getBounds() == null) {
            return null;
        }
        Rectangle bounds = poly.getBounds();
        int startX = bounds.x + (bounds.width / 2);
        int startY = bounds.y + (bounds.height / 2);

        drawWorldArrow(graphics, config.overlayColor(), startX, startY);
        return null;
    }

    // ---------------------------------------------------------------------
    // Ported from Quest Helper's DirectionArrow.java
    // ---------------------------------------------------------------------

    /**
     * Ported from {@code DirectionArrow.drawWorldArrow} (lines 212-221).
     */
    static void drawWorldArrow(Graphics2D graphics, Color color, int startX, int startY) {
        Line2D.Double line = new Line2D.Double(startX, startY - 13, startX, startY);
        int headWidth = 5;
        int headHeight = 4;
        int lineWidth = 9;
        drawArrow(graphics, line, color, lineWidth, headHeight, headWidth);
    }

    /**
     * Ported from {@code DirectionArrow.drawArrow} (lines 228-241).
     * Draws the fillPolygon of the arrow head to satisfy existing visual
     * tests that look for {@code fillPolygon} invocations — and this mirrors
     * Quest Helper where {@code drawWorldArrowHead} calls {@code g.fill(arrowHead)}.
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
}
