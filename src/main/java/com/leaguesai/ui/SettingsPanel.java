package com.leaguesai.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.function.Consumer;

public class SettingsPanel extends JPanel {

    private static final Color BACKGROUND_COLOR = new Color(30, 30, 30);
    private static final Color TEXT_COLOR = new Color(200, 200, 200);
    private static final Color INPUT_BACKGROUND = new Color(45, 45, 45);
    private static final Color BORDER_COLOR = new Color(60, 60, 60);
    private static final Color LABEL_COLOR = new Color(160, 160, 160);
    private static final Color WARNING_COLOR = new Color(255, 180, 50);
    private static final Color ERROR_TEXT_COLOR = new Color(220, 60, 60);

    private final JPasswordField apiKeyField;
    private final JLabel noApiKeyWarning;
    private final JCheckBox autoModeToggle;
    private final JTextField goalField;
    private final JButton setGoalButton;
    private final JButton refreshDataButton;
    private final JLabel dbStatusLabel;

    private Consumer<String> onGoalSet;
    private Consumer<String> onApiKeyChanged;
    private Runnable onRefreshData;

    public SettingsPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(BACKGROUND_COLOR);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // API Key section
        JLabel apiKeyLabel = createLabel("OpenAI API Key:");
        apiKeyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(apiKeyLabel);
        add(Box.createVerticalStrut(4));

        apiKeyField = new JPasswordField();
        apiKeyField.setBackground(INPUT_BACKGROUND);
        apiKeyField.setForeground(TEXT_COLOR);
        apiKeyField.setCaretColor(TEXT_COLOR);
        apiKeyField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        apiKeyField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
        ));
        apiKeyField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        apiKeyField.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(apiKeyField);
        add(Box.createVerticalStrut(2));

        // No-API-key warning label
        noApiKeyWarning = new JLabel("\u26A0 No API key set");
        noApiKeyWarning.setForeground(WARNING_COLOR);
        noApiKeyWarning.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        noApiKeyWarning.setAlignmentX(Component.LEFT_ALIGNMENT);
        noApiKeyWarning.setVisible(true);
        add(noApiKeyWarning);
        add(Box.createVerticalStrut(8));

        // Update warning visibility and fire callback on focus lost
        apiKeyField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                String key = getApiKey();
                noApiKeyWarning.setVisible(key.isEmpty());
                if (onApiKeyChanged != null) {
                    onApiKeyChanged.accept(key);
                }
            }
        });

        // Auto Mode toggle
        autoModeToggle = new JCheckBox("Auto Mode");
        autoModeToggle.setBackground(BACKGROUND_COLOR);
        autoModeToggle.setForeground(TEXT_COLOR);
        autoModeToggle.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        autoModeToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        autoModeToggle.setFocusPainted(false);
        add(autoModeToggle);
        add(Box.createVerticalStrut(10));

        // Goal section
        JLabel goalLabel = createLabel("Current Goal:");
        goalLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(goalLabel);
        add(Box.createVerticalStrut(4));

        JPanel goalRow = new JPanel(new BorderLayout(4, 0));
        goalRow.setBackground(BACKGROUND_COLOR);
        goalRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        goalRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        goalField = new JTextField();
        goalField.setBackground(INPUT_BACKGROUND);
        goalField.setForeground(TEXT_COLOR);
        goalField.setCaretColor(TEXT_COLOR);
        goalField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        goalField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
        ));

        setGoalButton = new JButton("Set");
        setGoalButton.setBackground(new Color(60, 100, 60));
        setGoalButton.setForeground(Color.WHITE);
        setGoalButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        setGoalButton.setFocusPainted(false);
        setGoalButton.setBorderPainted(false);
        setGoalButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        goalRow.add(goalField, BorderLayout.CENTER);
        goalRow.add(setGoalButton, BorderLayout.EAST);
        add(goalRow);
        add(Box.createVerticalStrut(10));

        // Refresh task data button
        refreshDataButton = new JButton("Refresh Task Data");
        refreshDataButton.setBackground(new Color(60, 80, 120));
        refreshDataButton.setForeground(Color.WHITE);
        refreshDataButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        refreshDataButton.setFocusPainted(false);
        refreshDataButton.setBorderPainted(false);
        refreshDataButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        refreshDataButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        refreshDataButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        add(refreshDataButton);
        add(Box.createVerticalStrut(8));

        // Database status label
        dbStatusLabel = new JLabel("Database: not found");
        dbStatusLabel.setForeground(LABEL_COLOR);
        dbStatusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        dbStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(dbStatusLabel);

        // Wire up button actions
        setGoalButton.addActionListener(e -> {
            if (onGoalSet != null) {
                onGoalSet.accept(goalField.getText().trim());
            }
        });

        goalField.addActionListener(e -> {
            if (onGoalSet != null) {
                onGoalSet.accept(goalField.getText().trim());
            }
        });

        refreshDataButton.addActionListener(e -> {
            if (onRefreshData != null) {
                onRefreshData.run();
            }
        });
    }

    private JLabel createLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(LABEL_COLOR);
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        return label;
    }

    public String getApiKey() {
        return new String(apiKeyField.getPassword()).trim();
    }

    public boolean isAutoMode() {
        return autoModeToggle.isSelected();
    }

    public void setGoalText(String goal) {
        goalField.setText(goal == null ? "" : goal);
    }

    public void setOnGoalSet(Consumer<String> callback) {
        this.onGoalSet = callback;
    }

    public void setOnApiKeyChanged(Consumer<String> callback) {
        this.onApiKeyChanged = callback;
    }

    public void setOnRefreshData(Runnable callback) {
        this.onRefreshData = callback;
    }

    public void setDatabaseStatus(String status, boolean isError) {
        dbStatusLabel.setText("Database: " + status);
        dbStatusLabel.setForeground(isError ? ERROR_TEXT_COLOR : LABEL_COLOR);
    }
}
