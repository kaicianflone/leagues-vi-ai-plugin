package com.leaguesai.ui;

import javax.swing.*;
import java.awt.*;

public class AdvicePanel extends JPanel {

    private static final Color BACKGROUND_COLOR = new Color(30, 30, 30);
    private static final Color TEXT_COLOR = new Color(200, 200, 200);
    private static final Color ERROR_COLOR = new Color(220, 60, 60);
    private static final Color NEXT_STEPS_COLOR = new Color(255, 220, 50);
    private static final Color INPUT_BACKGROUND = new Color(45, 45, 45);
    private static final Color BORDER_COLOR = new Color(60, 60, 60);
    private static final Color LABEL_COLOR = new Color(160, 160, 160);

    private final JLabel goalLabel;
    private final JLabel progressLabel;
    private final JTextArea adviceText;
    private final JTextArea nextStepsText;
    private final JButton refreshButton;

    private Runnable onRefresh;

    public AdvicePanel() {
        setLayout(new BorderLayout(0, 6));
        setBackground(BACKGROUND_COLOR);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Top info panel
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(BACKGROUND_COLOR);

        goalLabel = new JLabel("Goal: (none set)");
        goalLabel.setForeground(TEXT_COLOR);
        goalLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        goalLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        progressLabel = new JLabel("Progress: 0/0 tasks");
        progressLabel.setForeground(LABEL_COLOR);
        progressLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        topPanel.add(goalLabel);
        topPanel.add(Box.createVerticalStrut(2));
        topPanel.add(progressLabel);

        add(topPanel, BorderLayout.NORTH);

        // Center: advice text area
        adviceText = new JTextArea();
        adviceText.setEditable(false);
        adviceText.setLineWrap(true);
        adviceText.setWrapStyleWord(true);
        adviceText.setBackground(INPUT_BACKGROUND);
        adviceText.setForeground(LABEL_COLOR);
        adviceText.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
        adviceText.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        adviceText.setText("Click 'Refresh' to get advice based on your current state.");

        JScrollPane adviceScroll = new JScrollPane(adviceText);
        adviceScroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        adviceScroll.setBackground(BACKGROUND_COLOR);
        adviceScroll.getViewport().setBackground(INPUT_BACKGROUND);

        // Next steps text area
        JLabel nextStepsLabel = new JLabel("Next Steps:");
        nextStepsLabel.setForeground(LABEL_COLOR);
        nextStepsLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));

        nextStepsText = new JTextArea();
        nextStepsText.setEditable(false);
        nextStepsText.setLineWrap(true);
        nextStepsText.setWrapStyleWord(true);
        nextStepsText.setBackground(INPUT_BACKGROUND);
        nextStepsText.setForeground(NEXT_STEPS_COLOR);
        nextStepsText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        nextStepsText.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        nextStepsText.setRows(3);

        JScrollPane nextStepsScroll = new JScrollPane(nextStepsText);
        nextStepsScroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        nextStepsScroll.setBackground(BACKGROUND_COLOR);
        nextStepsScroll.getViewport().setBackground(INPUT_BACKGROUND);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 4));
        centerPanel.setBackground(BACKGROUND_COLOR);
        centerPanel.add(adviceScroll, BorderLayout.CENTER);

        JPanel nextStepsPanel = new JPanel(new BorderLayout(0, 2));
        nextStepsPanel.setBackground(BACKGROUND_COLOR);
        nextStepsPanel.add(nextStepsLabel, BorderLayout.NORTH);
        nextStepsPanel.add(nextStepsScroll, BorderLayout.CENTER);

        centerPanel.add(nextStepsPanel, BorderLayout.SOUTH);

        add(centerPanel, BorderLayout.CENTER);

        // Bottom: refresh button
        refreshButton = new JButton("Refresh Advice");
        refreshButton.setBackground(new Color(60, 80, 120));
        refreshButton.setForeground(Color.WHITE);
        refreshButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        refreshButton.setFocusPainted(false);
        refreshButton.setBorderPainted(false);
        refreshButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        bottomPanel.setBackground(BACKGROUND_COLOR);
        bottomPanel.add(refreshButton);

        add(bottomPanel, BorderLayout.SOUTH);

        refreshButton.addActionListener(e -> {
            if (onRefresh != null) {
                onRefresh.run();
            }
        });
    }

    public void setAdvice(String advice) {
        adviceText.setForeground(TEXT_COLOR);
        adviceText.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        adviceText.setText(advice);
    }

    public void setGoal(String goal) {
        goalLabel.setText("Goal: " + (goal == null || goal.isEmpty() ? "(none set)" : goal));
    }

    public void setProgress(int completed, int total) {
        progressLabel.setText("Progress: " + completed + "/" + total + " tasks");
    }

    public void setNextSteps(String nextSteps) {
        nextStepsText.setText(nextSteps == null ? "" : nextSteps);
    }

    public void setOnRefresh(Runnable callback) {
        this.onRefresh = callback;
    }

    public void setLoading(boolean loading) {
        refreshButton.setEnabled(!loading);
        if (loading) {
            adviceText.setForeground(LABEL_COLOR);
            adviceText.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
            adviceText.setText("Loading advice...");
        }
    }

    public void showError(String message) {
        adviceText.setForeground(ERROR_COLOR);
        adviceText.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        adviceText.setText("Error: " + message);
        refreshButton.setEnabled(true);
    }
}
