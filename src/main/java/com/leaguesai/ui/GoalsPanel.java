package com.leaguesai.ui;

import com.leaguesai.agent.PlannedStep;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.util.List;

/**
 * Replaces the old AdvicePanel. Hosts:
 *
 * <ul>
 *   <li>Goal title + progress label</li>
 *   <li>Coach review banner (B0aty / Faux / UIM verdict from {@code PersonaReviewer})</li>
 *   <li>{@link GoalAccordion} of tasks → expand to items + subtasks + wiki</li>
 *   <li>Heartbeat label (driven by {@code HeartbeatTicker})</li>
 *   <li>"Open chat" link button at the top</li>
 * </ul>
 *
 * <p>No more "Refresh advice" button — the heartbeat replaces it.
 */
public class GoalsPanel extends JPanel {

    private static final Color BACKGROUND_COLOR = new Color(30, 30, 30);
    private static final Color TEXT_COLOR = new Color(220, 220, 220);
    private static final Color LABEL_COLOR = new Color(160, 160, 160);
    private static final Color BANNER_BG = new Color(50, 40, 30);
    private static final Color BANNER_FG = new Color(255, 200, 120);
    private static final Color HEARTBEAT_FG = new Color(150, 200, 150);
    private static final Color LINK_FG = new Color(120, 170, 240);
    private static final Color BORDER_COLOR = new Color(60, 60, 60);

    private final JLabel goalLabel;
    private final JLabel progressLabel;
    private final JLabel reviewBanner;
    private final JPanel reviewWrapper;
    private final GoalAccordion accordion;
    private final JLabel heartbeatLabel;
    private final JLabel emptyStateLabel;
    private final JButton chatLinkButton;
    private UnlockablesPanel unlockables;
    private JPanel centerColumn;
    private java.awt.Component unlockablesSlot;

    private Runnable onOpenChat;

    public GoalsPanel() {
        setLayout(new BorderLayout(0, 6));
        setBackground(BACKGROUND_COLOR);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // ---- Top: nav link + goal/progress + review banner ----
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(BACKGROUND_COLOR);

        // Plain text — emojis like 💬 don't render in Swing's default font on
        // macOS and break the button's preferred size, clipping the label.
        chatLinkButton = new JButton("\u2190 Chat");
        chatLinkButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        chatLinkButton.setBackground(new Color(55, 55, 55));
        chatLinkButton.setForeground(LINK_FG);
        chatLinkButton.setFocusPainted(false);
        chatLinkButton.setBorderPainted(false);
        chatLinkButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        chatLinkButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        chatLinkButton.addActionListener(e -> {
            if (onOpenChat != null) onOpenChat.run();
        });

        JPanel linkRow = new JPanel(new BorderLayout());
        linkRow.setBackground(BACKGROUND_COLOR);
        linkRow.add(chatLinkButton, BorderLayout.EAST);
        linkRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Cap height so BoxLayout.Y_AXIS doesn't stretch the link row vertically.
        linkRow.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 24));

        goalLabel = new JLabel("Goal: (none set)");
        goalLabel.setForeground(TEXT_COLOR);
        goalLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        goalLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        progressLabel = new JLabel("Progress: 0/0 tasks");
        progressLabel.setForeground(LABEL_COLOR);
        progressLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Review banner — JLabel with HTML wrapping. JLabel inside BoxLayout
        // wraps reliably when given an explicit width hint, unlike a JTextArea
        // which fights with BoxLayout sizing on PluginPanel.
        reviewBanner = new JLabel(" ");
        reviewBanner.setOpaque(true);
        reviewBanner.setBackground(BANNER_BG);
        reviewBanner.setForeground(BANNER_FG);
        reviewBanner.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        reviewBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        reviewBanner.setVerticalAlignment(javax.swing.SwingConstants.TOP);

        reviewWrapper = new JPanel(new BorderLayout());
        reviewWrapper.setBackground(BACKGROUND_COLOR);
        reviewWrapper.add(reviewBanner, BorderLayout.CENTER);
        reviewWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        reviewWrapper.setVisible(false);

        top.add(linkRow);
        top.add(goalLabel);
        top.add(javax.swing.Box.createVerticalStrut(2));
        top.add(progressLabel);
        top.add(javax.swing.Box.createVerticalStrut(6));
        top.add(reviewWrapper);

        // ---- Center: single scroll column holding [top block, unlockables
        //      placeholder, empty state, accordion] so all three sections
        //      scroll together. Heartbeat stays pinned at SOUTH. ----
        accordion = new GoalAccordion();
        accordion.setAlignmentX(Component.LEFT_ALIGNMENT);

        emptyStateLabel = new JLabel(
                "<html><div style='text-align:center;width:170px;color:#888'>"
                        + "No plan loaded yet.<br>Ask the chat to plan something."
                        + "</div></html>");
        emptyStateLabel.setHorizontalAlignment(JLabel.CENTER);
        emptyStateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        top.setAlignmentX(Component.LEFT_ALIGNMENT);

        centerColumn = new JPanel();
        centerColumn.setLayout(new BoxLayout(centerColumn, BoxLayout.Y_AXIS));
        centerColumn.setBackground(BACKGROUND_COLOR);
        centerColumn.add(top);
        centerColumn.add(javax.swing.Box.createVerticalStrut(4));
        // Unlockables goes at index 2 via setUnlockablesPanel(...) once the
        // plugin has a TaskRepository. Until then this slot is a thin strut.
        unlockablesSlot = javax.swing.Box.createVerticalStrut(0);
        centerColumn.add(unlockablesSlot);
        centerColumn.add(javax.swing.Box.createVerticalStrut(4));
        centerColumn.add(emptyStateLabel);
        centerColumn.add(accordion);

        JScrollPane scroll = new JScrollPane(centerColumn);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BACKGROUND_COLOR);
        // Safety net: if any row decides to be wider than the viewport
        // (e.g. a very long word that can't wrap), fall back to a
        // horizontal scrollbar instead of silently clipping.
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        emptyStateLabel.setVisible(true);

        // ---- Bottom: heartbeat ----
        heartbeatLabel = new JLabel(" ");
        heartbeatLabel.setForeground(HEARTBEAT_FG);
        heartbeatLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        heartbeatLabel.setBorder(BorderFactory.createEmptyBorder(6, 4, 2, 4));

        add(heartbeatLabel, BorderLayout.SOUTH);
    }

    public void setGoal(String goal) {
        SwingUtilities.invokeLater(() ->
                goalLabel.setText("Goal: " + (goal == null || goal.isEmpty() ? "(none set)" : goal)));
    }

    public void setProgress(int completed, int total) {
        SwingUtilities.invokeLater(() ->
                progressLabel.setText("Progress: " + completed + "/" + total + " tasks"));
    }

    public void setReviewBanner(String review) {
        SwingUtilities.invokeLater(() -> {
            if (review == null || review.isEmpty()) {
                reviewBanner.setText(" ");
                reviewWrapper.setVisible(false);
            } else {
                // HTML with explicit width hint so JLabel wraps inside the
                // ~225px PluginPanel column. Newlines become <br>.
                String safe = escapeHtml(review).replace("\n", "<br>");
                reviewBanner.setText("<html><div style='width:190px'>" + safe + "</div></html>");
                reviewWrapper.setVisible(true);
            }
            revalidate();
            repaint();
        });
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public void setSteps(List<PlannedStep> steps) {
        SwingUtilities.invokeLater(() -> {
            accordion.setSteps(steps);
            emptyStateLabel.setVisible(steps == null || steps.isEmpty());
            revalidate();
            repaint();
        });
    }

    public void setHeartbeatText(String text) {
        SwingUtilities.invokeLater(() -> {
            if (text == null || text.isEmpty()) {
                heartbeatLabel.setText(" ");
            } else {
                // Width-hinted HTML so JLabel word-wraps inside the ~210px
                // side panel instead of clipping. Same pattern as the
                // review banner above.
                String safe = escapeHtml(text).replace("\n", "<br>");
                heartbeatLabel.setText("<html><div style='width:195px'>" + safe + "</div></html>");
            }
            heartbeatLabel.revalidate();
        });
    }

    public void setOnOpenChat(Runnable callback) {
        this.onOpenChat = callback;
    }

    /**
     * Mount the unlockables goal picker (relics + areas + pacts) into the
     * center scroll column. Swaps out the initial empty strut so the layout
     * reflows. Safe to call multiple times — a subsequent call replaces the
     * previous panel.
     */
    public void setUnlockablesPanel(UnlockablesPanel panel) {
        SwingUtilities.invokeLater(() -> {
            if (centerColumn == null || unlockablesSlot == null) return;
            int idx = -1;
            for (int i = 0; i < centerColumn.getComponentCount(); i++) {
                if (centerColumn.getComponent(i) == unlockablesSlot) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) return;
            centerColumn.remove(idx);
            if (panel != null) {
                panel.setAlignmentX(Component.LEFT_ALIGNMENT);
                centerColumn.add(panel, idx);
                unlockablesSlot = panel;
                this.unlockables = panel;
            } else {
                java.awt.Component strut = javax.swing.Box.createVerticalStrut(0);
                centerColumn.add(strut, idx);
                unlockablesSlot = strut;
                this.unlockables = null;
            }
            centerColumn.revalidate();
            centerColumn.repaint();
        });
    }

    public UnlockablesPanel getUnlockablesPanel() {
        return unlockables;
    }
}
