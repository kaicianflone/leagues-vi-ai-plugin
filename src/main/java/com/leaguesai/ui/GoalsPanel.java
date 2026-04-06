package com.leaguesai.ui;

import com.leaguesai.agent.PlannedStep;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
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
    private final JTextArea reviewBanner;
    private final JScrollPane reviewScroll;
    private final GoalAccordion accordion;
    private final JLabel heartbeatLabel;
    private final JLabel emptyStateLabel;
    private final JButton chatLinkButton;

    private Runnable onOpenChat;

    public GoalsPanel() {
        setLayout(new BorderLayout(0, 6));
        setBackground(BACKGROUND_COLOR);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // ---- Top: nav link + goal/progress + review banner ----
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(BACKGROUND_COLOR);

        chatLinkButton = new JButton("\uD83D\uDCAC Chat");
        chatLinkButton.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
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

        goalLabel = new JLabel("Goal: (none set)");
        goalLabel.setForeground(TEXT_COLOR);
        goalLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        goalLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        progressLabel = new JLabel("Progress: 0/0 tasks");
        progressLabel.setForeground(LABEL_COLOR);
        progressLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Review banner — multiline collapsible-ish text area
        reviewBanner = new JTextArea();
        reviewBanner.setEditable(false);
        reviewBanner.setLineWrap(true);
        reviewBanner.setWrapStyleWord(true);
        reviewBanner.setBackground(BANNER_BG);
        reviewBanner.setForeground(BANNER_FG);
        reviewBanner.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        reviewBanner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        reviewBanner.setRows(5);

        reviewScroll = new JScrollPane(reviewBanner);
        reviewScroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        reviewScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        reviewScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        reviewScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        reviewScroll.setVisible(false);

        top.add(linkRow);
        top.add(goalLabel);
        top.add(javax.swing.Box.createVerticalStrut(2));
        top.add(progressLabel);
        top.add(javax.swing.Box.createVerticalStrut(6));
        top.add(reviewScroll);

        add(top, BorderLayout.NORTH);

        // ---- Center: accordion (or empty state) ----
        accordion = new GoalAccordion();
        emptyStateLabel = new JLabel(
                "<html><div style='text-align:center;width:170px;color:#888'>"
                        + "No plan loaded yet.<br>Ask the chat to plan something."
                        + "</div></html>");
        emptyStateLabel.setHorizontalAlignment(JLabel.CENTER);

        JPanel centerWrapper = new JPanel(new BorderLayout());
        centerWrapper.setBackground(BACKGROUND_COLOR);
        JScrollPane scroll = new JScrollPane(accordion);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BACKGROUND_COLOR);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        centerWrapper.add(scroll, BorderLayout.CENTER);
        centerWrapper.add(emptyStateLabel, BorderLayout.NORTH);
        emptyStateLabel.setVisible(true);

        add(centerWrapper, BorderLayout.CENTER);

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
                reviewBanner.setText("");
                reviewScroll.setVisible(false);
            } else {
                reviewBanner.setText(review);
                reviewBanner.setCaretPosition(0);
                reviewScroll.setVisible(true);
            }
            revalidate();
            repaint();
        });
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
        SwingUtilities.invokeLater(() ->
                heartbeatLabel.setText(text == null || text.isEmpty() ? " " : text));
    }

    public void setOnOpenChat(Runnable callback) {
        this.onOpenChat = callback;
    }
}
