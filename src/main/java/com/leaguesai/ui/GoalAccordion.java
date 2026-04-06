package com.leaguesai.ui;

import com.leaguesai.agent.PlannedStep;
import com.leaguesai.data.model.Task;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vertical stack of collapsible task rows. Click a header to expand into:
 *
 * <ul>
 *   <li>Required items (with one-line ironman acquisition path each, sourced
 *       from {@link PlannedStep#getItemSourceNotes()})</li>
 *   <li>Subtasks (the prereq chain — task IDs in {@code Task.tasksRequired}
 *       resolved against the same plan list)</li>
 *   <li>Wiki link (if present)</li>
 * </ul>
 *
 * <p>Lightweight: just nested {@code JPanel}s + a click handler. No animation.
 * Initial state: all collapsed.
 */
public class GoalAccordion extends JPanel {

    private static final Color ROW_BG = new Color(45, 45, 45);
    private static final Color ROW_BG_HOVER = new Color(55, 55, 55);
    private static final Color CHILD_BG = new Color(38, 38, 38);
    private static final Color HEADER_FG = new Color(220, 220, 220);
    private static final Color META_FG = new Color(150, 150, 150);
    private static final Color ITEM_FG = new Color(255, 200, 80);
    private static final Color SOURCE_FG = new Color(180, 200, 180);
    private static final Color SUBTASK_FG = new Color(180, 180, 220);
    private static final Color LINK_FG = new Color(120, 170, 240);
    private static final Color BORDER = new Color(60, 60, 60);

    public GoalAccordion() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(30, 30, 30));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    }

    /**
     * Replace the current accordion content with a new plan. Called from EDT.
     */
    public void setSteps(List<PlannedStep> steps) {
        removeAll();
        if (steps == null || steps.isEmpty()) {
            JLabel empty = new JLabel("No tasks in this plan yet.");
            empty.setForeground(META_FG);
            empty.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(empty);
            revalidate();
            repaint();
            return;
        }

        // Build an id → instruction lookup so the prereq chain renders by name.
        Map<String, String> idToName = new HashMap<>();
        for (PlannedStep s : steps) {
            if (s != null && s.getTask() != null && s.getTask().getId() != null) {
                idToName.put(s.getTask().getId(),
                        s.getInstruction() != null ? s.getInstruction() : s.getTask().getName());
            }
        }

        for (int i = 0; i < steps.size(); i++) {
            PlannedStep step = steps.get(i);
            if (step == null) continue;
            add(buildRow(i + 1, step, idToName));
            add(javax.swing.Box.createVerticalStrut(2));
        }

        revalidate();
        repaint();
    }

    private JPanel buildRow(int index, PlannedStep step, Map<String, String> idToName) {
        // Outer wrapper holds header + (lazy) child
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setBackground(ROW_BG);
        wrapper.setBorder(BorderFactory.createLineBorder(BORDER));
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // Header
        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setBackground(ROW_BG);
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        final JLabel chevron = new JLabel("\u25B6");
        chevron.setForeground(META_FG);
        chevron.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));

        String instruction = step.getInstruction() != null ? step.getInstruction() : "(step)";
        if (instruction.length() > 60) instruction = instruction.substring(0, 57) + "...";
        JLabel title = new JLabel(index + ". " + instruction);
        title.setForeground(HEADER_FG);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.setOpaque(false);
        left.add(chevron);
        left.add(javax.swing.Box.createHorizontalStrut(6));
        left.add(title);

        StringBuilder meta = new StringBuilder();
        Task t = step.getTask();
        if (t != null) {
            if (t.getDifficulty() != null) {
                meta.append(t.getDifficulty().name().toLowerCase());
            }
            if (t.getPoints() > 0) {
                if (meta.length() > 0) meta.append(" / ");
                meta.append(t.getPoints()).append("pts");
            }
        }
        JLabel metaLabel = new JLabel(meta.toString(), SwingConstants.RIGHT);
        metaLabel.setForeground(META_FG);
        metaLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));

        header.add(left, BorderLayout.WEST);
        header.add(metaLabel, BorderLayout.EAST);

        // Child panel — built once, toggled visible.
        final JPanel child = buildChild(step, idToName);
        child.setVisible(false);

        wrapper.add(header);
        wrapper.add(child);

        // Toggle on click
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                boolean nowVisible = !child.isVisible();
                child.setVisible(nowVisible);
                chevron.setText(nowVisible ? "\u25BC" : "\u25B6");
                wrapper.revalidate();
                wrapper.repaint();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                header.setBackground(ROW_BG_HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                header.setBackground(ROW_BG);
            }
        });

        return wrapper;
    }

    private JPanel buildChild(PlannedStep step, Map<String, String> idToName) {
        JPanel child = new JPanel();
        child.setLayout(new BoxLayout(child, BoxLayout.Y_AXIS));
        child.setBackground(CHILD_BG);
        child.setBorder(BorderFactory.createEmptyBorder(6, 24, 8, 8));

        Task t = step.getTask();
        Map<String, String> sources = step.getItemSourceNotes() != null
                ? step.getItemSourceNotes() : java.util.Collections.emptyMap();

        // Required items
        if (t != null && t.getItemsRequired() != null && !t.getItemsRequired().isEmpty()) {
            child.add(makeSectionLabel("Required items"));
            for (Map.Entry<String, Integer> e : t.getItemsRequired().entrySet()) {
                String name = e.getKey();
                int qty = e.getValue() != null ? e.getValue() : 1;
                String suffix = qty > 1 ? " \u00D7" + qty : "";
                JLabel itemLine = new JLabel("\u2022 " + name + suffix);
                itemLine.setForeground(ITEM_FG);
                itemLine.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                itemLine.setAlignmentX(Component.LEFT_ALIGNMENT);
                child.add(itemLine);

                String src = sources.get(name);
                if (src != null && !src.isEmpty()) {
                    JLabel srcLine = new JLabel("    " + src);
                    srcLine.setForeground(SOURCE_FG);
                    srcLine.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 10));
                    srcLine.setAlignmentX(Component.LEFT_ALIGNMENT);
                    child.add(srcLine);
                }
            }
            child.add(javax.swing.Box.createVerticalStrut(4));
        }

        // Subtasks (prereq chain)
        if (t != null && t.getTasksRequired() != null && !t.getTasksRequired().isEmpty()) {
            List<String> resolved = new ArrayList<>();
            for (String prereqId : t.getTasksRequired()) {
                String name = idToName.get(prereqId);
                if (name != null) resolved.add(name);
            }
            if (!resolved.isEmpty()) {
                child.add(makeSectionLabel("Subtasks (prereq chain)"));
                int n = 1;
                for (String name : resolved) {
                    JLabel line = new JLabel(n++ + ". " + name);
                    line.setForeground(SUBTASK_FG);
                    line.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                    line.setAlignmentX(Component.LEFT_ALIGNMENT);
                    child.add(line);
                }
                child.add(javax.swing.Box.createVerticalStrut(4));
            }
        }

        // Wiki link
        if (t != null && t.getWikiUrl() != null && !t.getWikiUrl().isEmpty()) {
            JLabel wiki = new JLabel("Wiki: " + t.getWikiUrl());
            wiki.setForeground(LINK_FG);
            wiki.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            wiki.setAlignmentX(Component.LEFT_ALIGNMENT);
            child.add(wiki);
        }

        // Empty-state for the child if there's literally nothing to show
        if (child.getComponentCount() == 0) {
            JLabel none = new JLabel("(no extra detail)");
            none.setForeground(META_FG);
            none.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 10));
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            child.add(none);
        }

        return child;
    }

    private JLabel makeSectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(META_FG);
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }
}
