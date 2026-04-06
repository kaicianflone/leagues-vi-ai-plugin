package com.leaguesai.ui;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.util.EnumMap;
import java.util.Map;

public class AsciiSpriteRenderer extends JPanel {

    private static final Color BACKGROUND_COLOR = new Color(30, 30, 30);
    private static final Color TEXT_COLOR = new Color(0, 200, 0);
    private static final Font MONO_FONT = new Font(Font.MONOSPACED, Font.BOLD, 12);
    private static final Dimension PREFERRED_SIZE = new Dimension(200, 80);

    private final Map<AnimationType, SpriteAnimation> animations = new EnumMap<>(AnimationType.class);
    private AnimationType currentType = AnimationType.IDLE;
    private int currentFrame = 0;
    private Timer timer;

    public AsciiSpriteRenderer(int frameIntervalMs) {
        setBackground(BACKGROUND_COLOR);
        setPreferredSize(PREFERRED_SIZE);
        setFont(MONO_FONT);

        for (AnimationType type : AnimationType.values()) {
            String resourcePath = "/sprites/" + type.name().toLowerCase() + ".txt";
            animations.put(type, SpriteAnimation.loadFromResource(resourcePath));
        }

        timer = new Timer(frameIntervalMs, e -> {
            currentFrame++;
            repaint();
        });
        timer.start();
    }

    public void setAnimation(AnimationType type) {
        if (type != null && type != currentType) {
            currentType = type;
            currentFrame = 0;
            repaint();
        }
    }

    public void pause() {
        if (timer != null) {
            timer.stop();
        }
    }

    public void resume() {
        if (timer != null) {
            timer.start();
        }
    }

    /** Stop and release the animation timer. Safe to call multiple times. */
    public void dispose() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        SpriteAnimation anim = animations.getOrDefault(currentType, animations.get(AnimationType.IDLE));
        if (anim == null) {
            return;
        }

        String[] frame = anim.getFrame(currentFrame);
        if (frame == null || frame.length == 0) {
            return;
        }

        g.setFont(MONO_FONT);
        g.setColor(TEXT_COLOR);

        FontMetrics fm = g.getFontMetrics();
        int lineHeight = fm.getHeight();
        int totalHeight = frame.length * lineHeight;
        int startY = (getHeight() - totalHeight) / 2 + fm.getAscent();

        for (int i = 0; i < frame.length; i++) {
            String line = frame[i];
            int lineWidth = fm.stringWidth(line);
            int x = (getWidth() - lineWidth) / 2;
            int y = startY + i * lineHeight;
            g.drawString(line, x, y);
        }
    }
}
