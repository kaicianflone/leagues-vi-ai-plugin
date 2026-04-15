package com.leaguesai.overlay;

import com.leaguesai.agent.PlannedStep;
import com.leaguesai.data.model.Task;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * Always-on HUD overlay that shows the current planned step's task name,
 * step index, difficulty, and wiki URL. Active whenever a plan step is set,
 * even when the step has no spatial data (location / NPC / object targets).
 *
 * <p>Positioned top-left so it doesn't occlude the minimap or chat.
 */
@Slf4j
@Singleton
public class StepInstructionOverlay extends Overlay {

    private static final int TASK_NAME_MAX_LEN = 36;
    private static final int TASK_NAME_TRUNCATE_LEN = 33;
    private static final int URL_MAX_LEN = 28;
    private static final int URL_TRUNCATE_LEN = 25;
    private static final int PANEL_WIDTH = 220;
    private static final Color WIKI_LINK_COLOR = new Color(100, 180, 255);

    private volatile PlannedStep activeStep;
    private volatile int stepIndex;
    private volatile int totalSteps;

    private final PanelComponent panelComponent = new PanelComponent();

    @Inject
    public StepInstructionOverlay() {
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        panelComponent.setPreferredSize(new Dimension(PANEL_WIDTH, 0));
    }

    public void setActiveStep(PlannedStep step, int index, int total) {
        this.activeStep = step;
        this.stepIndex = index;
        this.totalSteps = total;
    }

    public void clear() {
        this.activeStep = null;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        PlannedStep step = activeStep;
        if (step == null) {
            return null;
        }

        Task task = step.getTask();
        String taskName = task != null ? task.getName() : step.getInstruction();
        if (taskName == null || taskName.isEmpty()) {
            return null;
        }

        panelComponent.getChildren().clear();

        // Header: "Step N / M"
        int idx = stepIndex + 1;
        int tot = totalSteps;
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Leagues AI — Step " + idx + (tot > 0 ? " / " + tot : ""))
                .color(Color.WHITE)
                .build());

        // Task name (wrap long names)
        String displayName = taskName.length() > TASK_NAME_MAX_LEN ? taskName.substring(0, TASK_NAME_TRUNCATE_LEN) + "..." : taskName;
        panelComponent.getChildren().add(LineComponent.builder()
                .left(displayName)
                .leftColor(Color.YELLOW)
                .build());

        // Difficulty + area badge
        if (task != null) {
            String badge = "";
            if (task.getDifficulty() != null) {
                badge = task.getDifficulty().name();
            }
            if (task.getArea() != null && !task.getArea().isEmpty()) {
                badge += (badge.isEmpty() ? "" : " · ") + capitalize(task.getArea());
            }
            if (task.getPoints() > 0) {
                badge += (badge.isEmpty() ? "" : " · ") + task.getPoints() + " pts";
            }
            if (!badge.isEmpty()) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left(badge)
                        .leftColor(Color.LIGHT_GRAY)
                        .build());
            }
        }

        // Wiki hint when no spatial data
        boolean hasSpatial = step.getDestination() != null
                || (step.getOverlayData() != null
                    && (notEmpty(step.getOverlayData().getTargetNpcIds())
                        || notEmpty(step.getOverlayData().getTargetObjectIds())
                        || notEmpty(step.getOverlayData().getTargetItemIds())));
        if (!hasSpatial && task != null && task.getWikiUrl() != null && !task.getWikiUrl().isEmpty()) {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Wiki: " + shortUrl(task.getWikiUrl()))
                    .leftColor(WIKI_LINK_COLOR)
                    .build());
        }

        return panelComponent.render(graphics);
    }

    private static boolean notEmpty(java.util.List<?> list) {
        return list != null && !list.isEmpty();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String shortUrl(String url) {
        // Show just the page title from the wiki URL
        int slash = url.lastIndexOf('/');
        String page = slash >= 0 ? url.substring(slash + 1) : url;
        page = page.replace('_', ' ');
        return page.length() > URL_MAX_LEN ? page.substring(0, URL_TRUNCATE_LEN) + "..." : page;
    }
}
