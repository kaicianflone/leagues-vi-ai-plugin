package com.leaguesai.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

/**
 * Manages a single world-map marker via WorldMapPointManager.
 * Does not extend Overlay — world map markers are managed by the manager.
 */
@Singleton
public class WorldMapOverlay {

    private final Client client;
    private final LeaguesAiConfig config;
    private WorldMapPointManager manager;

    @Getter
    private WorldMapPoint currentPoint;

    @Getter
    private WorldPoint targetPoint;

    @Inject
    public WorldMapOverlay(Client client, LeaguesAiConfig config) {
        this(client, config, null);
    }

    /** Test constructor: pass an explicit manager (or null for no-op mode). */
    public WorldMapOverlay(Client client, LeaguesAiConfig config, WorldMapPointManager manager) {
        this.client = client;
        this.config = config;
        this.manager = manager;
    }

    /**
     * Optional manager injection: if {@link WorldMapPointManager} is bound in
     * the Guice context (i.e. running inside RuneLite), Guice will call this
     * method. Otherwise the field stays null and world-map updates are no-ops.
     */
    @Inject(optional = true)
    public void setManager(WorldMapPointManager manager) {
        this.manager = manager;
    }

    public void update(WorldPoint point, BufferedImage icon) {
        clear();
        if (point == null) {
            return;
        }
        this.targetPoint = point;
        if (manager == null) {
            return;
        }
        BufferedImage image = icon != null ? icon : buildDefaultIcon(config.overlayColor());
        // Quest Helper-style marker: snaps to the world map edge as a direction
        // arrow when the target is off-screen, and jumps/centers the world map
        // on the target when clicked. Mirrors the pattern used in
        // com.questhelper.steps.tools.QuestWorldMapPoint (Quest Helper repo).
        WorldMapPoint wmp = WorldMapPoint.builder()
            .worldPoint(point)
            .image(image)
            .name("Leagues AI Target")
            .tooltip("Leagues AI: next planned step")
            .snapToEdge(true)
            .jumpOnClick(true)
            .build();
        this.currentPoint = wmp;
        manager.add(wmp);
    }

    /**
     * Builds a 28x64 map pin marker icon: a filled teardrop / pin shape with
     * a dark outline, pointing down so its tip sits on the target world tile.
     *
     * <p>This is NOT a copy of Quest Helper's PNG sprite — it's a classic map
     * pin silhouette rendered programmatically so we don't redistribute any
     * binary asset. The shape is a circle for the head fused with a triangle
     * for the point.
     *
     * <p>The image is padded so that the pin's TIP sits at the vertical
     * center of the image (row 32 of 64). RuneLite's WorldMapPoint centers
     * the image on the world point by default, so this makes the tip land
     * exactly on the target tile.
     */
    private BufferedImage buildDefaultIcon(Color color) {
        int w = 28;
        int h = 64;
        int pinHeight = 32;
        // Bottom padding so the pin tip lands at image center (y = h/2 = 32)
        // The pin starts at y = (h/2 - pinHeight) = 0 and tip is at y = 32.
        int yStart = 0;
        int tipY = yStart + pinHeight - 1; // 31
        int headDiameter = 20;
        int headX = (w - headDiameter) / 2; // 4
        int headY = yStart + 1;             // 1

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            // Pin head: circle at the top
            Ellipse2D head = new Ellipse2D.Double(headX, headY, headDiameter, headDiameter);
            // Pin tip: triangle from the bottom of the head down to the image center
            Polygon tip = new Polygon(
                    new int[] {headX + 4, headX + headDiameter - 4, w / 2},
                    new int[] {headY + headDiameter - 3, headY + headDiameter - 3, tipY},
                    3);
            // Union the two shapes into a single pin silhouette
            Area pin = new Area(head);
            pin.add(new Area(tip));

            // Drop shadow
            g.setColor(new Color(0, 0, 0, 110));
            g.translate(1, 1);
            g.fill(pin);
            g.translate(-1, -1);

            // Fill in the overlay color
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 235));
            g.fill(pin);

            // Dark outline
            g.setColor(new Color(20, 20, 20, 230));
            g.setStroke(new BasicStroke(1.8f));
            g.draw(pin);

            // Inner dot (white) to make it pop
            int dotX = w / 2 - 3;
            int dotY = headY + headDiameter / 2 - 3;
            g.setColor(new Color(255, 255, 255, 230));
            g.fillOval(dotX, dotY, 6, 6);
            g.setColor(new Color(20, 20, 20, 220));
            g.setStroke(new BasicStroke(1.0f));
            g.drawOval(dotX, dotY, 6, 6);
        } finally {
            g.dispose();
        }
        return img;
    }

    public void clear() {
        if (manager != null && currentPoint != null) {
            manager.remove(currentPoint);
        }
        this.currentPoint = null;
        this.targetPoint = null;
    }
}
