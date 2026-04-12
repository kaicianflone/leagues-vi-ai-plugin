package com.leaguesai.ui;

import com.leaguesai.data.UserPreferences;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JOptionPane;

public class SettingsPanel extends JPanel {

    private static final Color BACKGROUND_COLOR = new Color(30, 30, 30);
    private static final Color TEXT_COLOR = new Color(200, 200, 200);
    private static final Color INPUT_BACKGROUND = new Color(45, 45, 45);
    private static final Color BORDER_COLOR = new Color(60, 60, 60);
    private static final Color LABEL_COLOR = new Color(160, 160, 160);
    private static final Color WARNING_COLOR = new Color(255, 180, 50);
    private static final Color ERROR_TEXT_COLOR = new Color(220, 60, 60);

    private static final String[] MODEL_OPTIONS = {
            "gpt-5.4", "gpt-5", "gpt-4o", "gpt-4o-mini"
    };

    private static final String[][] PERSONA_DEFS = {
            {"points_chaser",  "Points Chaser — pts/hr, task batching, parallel completion"},
            {"build_architect","Build Architect — relic + pact synergy, gear milestones"},
            {"explorer",       "Explorer — Varlamore strategy, area unlock order"},
            {"sprinter",       "Sprinter — pace tracking, time-limited mindset"},
            {"theorist",       "Theorist — exact math, DPS calcs, synergy numbers"},
            {"settled",        "Settled — methodical, prereq chains, no shortcuts"},
            {"sirpugger",      "SirPugger — leagues meta, echo boss priorities"},
            {"pragmatist",     "Pragmatist — anchored to current levels and unlocks"},
            {"skill_spec",     "Skill Spec — XP/hr, skilling routes, level milestones"},
    };

    private final JLabel authModeLabel;
    private final JButton signInButton;
    private final JPasswordField apiKeyField;
    private final JLabel noApiKeyWarning;
    private final JCheckBox autoModeToggle;
    private final JTextField goalField;
    private final JButton setGoalButton;
    private final JButton refreshDataButton;
    private final JButton rescrapeButton;
    private final JLabel dbStatusLabel;
    private final JComboBox<String> modelDropdown;
    private final JCheckBox[] personaCheckboxes;

    private Consumer<String> onGoalSet;
    private Consumer<String> onApiKeyChanged;
    private Runnable onRefreshData;
    private Runnable onSignIn;
    private Consumer<String> onModelChanged;
    private Consumer<List<String>> onPersonasChanged;

    public SettingsPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(BACKGROUND_COLOR);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Active auth mode (set later by the plugin once it picks OAuth vs API key)
        authModeLabel = new JLabel("Auth: checking...");
        authModeLabel.setForeground(LABEL_COLOR);
        authModeLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        authModeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(authModeLabel);
        add(Box.createVerticalStrut(4));

        signInButton = new JButton("Sign in with ChatGPT");
        signInButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        signInButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        signInButton.addActionListener(e -> {
            if (onSignIn != null) {
                signInButton.setEnabled(false);
                signInButton.setText("Opening Terminal... complete login there");
                onSignIn.run();
            }
        });
        add(signInButton);
        add(Box.createVerticalStrut(10));

        // Model selector
        JLabel modelLabel = createLabel("Model:");
        modelLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(modelLabel);
        add(Box.createVerticalStrut(4));

        modelDropdown = new JComboBox<>(MODEL_OPTIONS);
        modelDropdown.setBackground(INPUT_BACKGROUND);
        modelDropdown.setForeground(TEXT_COLOR);
        modelDropdown.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        modelDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        modelDropdown.setAlignmentX(Component.LEFT_ALIGNMENT);
        modelDropdown.addActionListener(e -> {
            if (onModelChanged != null) {
                onModelChanged.accept((String) modelDropdown.getSelectedItem());
            }
        });
        add(modelDropdown);
        add(Box.createVerticalStrut(12));

        // Persona selection
        JLabel personaLabel = createLabel("Coaching Personas:");
        personaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(personaLabel);
        add(Box.createVerticalStrut(4));

        // Select all / deselect all row
        JPanel personaButtonRow = new JPanel(new GridLayout(1, 2, 4, 0));
        personaButtonRow.setBackground(BACKGROUND_COLOR);
        personaButtonRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        personaButtonRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton selectAllBtn = new JButton("All");
        selectAllBtn.setBackground(new Color(50, 70, 50));
        selectAllBtn.setForeground(Color.WHITE);
        selectAllBtn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        selectAllBtn.setFocusPainted(false);
        selectAllBtn.setBorderPainted(false);

        JButton deselectAllBtn = new JButton("None");
        deselectAllBtn.setBackground(new Color(70, 50, 50));
        deselectAllBtn.setForeground(Color.WHITE);
        deselectAllBtn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        deselectAllBtn.setFocusPainted(false);
        deselectAllBtn.setBorderPainted(false);

        personaButtonRow.add(selectAllBtn);
        personaButtonRow.add(deselectAllBtn);
        add(personaButtonRow);
        add(Box.createVerticalStrut(4));

        personaCheckboxes = new JCheckBox[PERSONA_DEFS.length];
        for (int i = 0; i < PERSONA_DEFS.length; i++) {
            JCheckBox cb = new JCheckBox("<html><span style='font-size:10px'>"
                    + PERSONA_DEFS[i][1] + "</span></html>", true);
            cb.setBackground(BACKGROUND_COLOR);
            cb.setForeground(TEXT_COLOR);
            cb.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            cb.setFocusPainted(false);
            cb.addActionListener(e -> firePersonasChanged());
            personaCheckboxes[i] = cb;
            add(cb);
        }

        selectAllBtn.addActionListener(e -> {
            for (JCheckBox cb : personaCheckboxes) cb.setSelected(true);
            firePersonasChanged();
        });
        deselectAllBtn.addActionListener(e -> {
            for (JCheckBox cb : personaCheckboxes) cb.setSelected(false);
            firePersonasChanged();
        });
        add(Box.createVerticalStrut(10));

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
        add(Box.createVerticalStrut(4));

        // Rescrape instructions button
        rescrapeButton = new JButton("Refresh tasks from wiki");
        rescrapeButton.setBackground(new Color(80, 60, 100));
        rescrapeButton.setForeground(Color.WHITE);
        rescrapeButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        rescrapeButton.setFocusPainted(false);
        rescrapeButton.setBorderPainted(false);
        rescrapeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        rescrapeButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        rescrapeButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        rescrapeButton.addActionListener(e -> JOptionPane.showMessageDialog(
                this,
                "To refresh task data:\n"
                        + "1. Run ./scraper/scrape.sh from the repo root\n"
                        + "2. Restart the plugin\n\n"
                        + "The scraper writes to:\n"
                        + "~/.runelite/leagues-ai/data/leagues-vi-tasks.db",
                "Refresh Tasks from Wiki",
                JOptionPane.INFORMATION_MESSAGE));
        add(rescrapeButton);
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

    public void setOnSignIn(Runnable handler) { this.onSignIn = handler; }

    public void setSignInButtonText(String text) {
        SwingUtilities.invokeLater(() -> signInButton.setText(text));
    }

    public void setSignInButtonEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> signInButton.setEnabled(enabled));
    }

    public void setSignInButtonVisible(boolean visible) {
        SwingUtilities.invokeLater(() -> signInButton.setVisible(visible));
    }

    public void setAuthMode(String label) {
        authModeLabel.setText(label);
        if (label.contains("ChatGPT OAuth")) {
            signInButton.setText("Re-authenticate with ChatGPT");
        } else {
            signInButton.setText("Sign in with ChatGPT");
        }
    }

    public void setAuthModeLabel(String mode) {
        setAuthMode(mode);
    }

    public void setDatabaseStatus(String status, boolean isError) {
        dbStatusLabel.setText("Database: " + status);
        dbStatusLabel.setForeground(isError ? ERROR_TEXT_COLOR : LABEL_COLOR);
    }

    // -------------------------------------------------------------------------
    // Model + Persona accessors
    // -------------------------------------------------------------------------

    public String getSelectedModel() {
        Object sel = modelDropdown.getSelectedItem();
        return sel != null ? sel.toString() : UserPreferences.DEFAULT_MODEL;
    }

    public void setSelectedModel(String model) {
        SwingUtilities.invokeLater(() -> modelDropdown.setSelectedItem(model));
    }

    public List<String> getSelectedPersonas() {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < personaCheckboxes.length; i++) {
            if (personaCheckboxes[i].isSelected()) {
                result.add(PERSONA_DEFS[i][0]);
            }
        }
        return result;
    }

    public void setSelectedPersonas(List<String> personas) {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < personaCheckboxes.length; i++) {
                personaCheckboxes[i].setSelected(personas.contains(PERSONA_DEFS[i][0]));
            }
        });
    }

    public void setOnModelChanged(Consumer<String> callback) {
        this.onModelChanged = callback;
    }

    public void setOnPersonasChanged(Consumer<List<String>> callback) {
        this.onPersonasChanged = callback;
    }

    private void firePersonasChanged() {
        if (onPersonasChanged != null) {
            onPersonasChanged.accept(getSelectedPersonas());
        }
    }
}
