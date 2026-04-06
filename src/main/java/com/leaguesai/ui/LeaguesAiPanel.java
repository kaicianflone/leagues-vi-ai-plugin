package com.leaguesai.ui;

import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import java.awt.*;

public class LeaguesAiPanel extends PluginPanel {

    private static final Color BACKGROUND_COLOR = new Color(30, 30, 30);
    private static final Color TEXT_COLOR = new Color(200, 200, 200);
    private static final Color LABEL_COLOR = new Color(160, 160, 160);
    private static final Color TAB_ACTIVE_COLOR = new Color(60, 100, 160);
    private static final Color TAB_INACTIVE_COLOR = new Color(45, 45, 45);
    private static final Color TAB_TEXT_COLOR = Color.WHITE;

    private final AsciiSpriteRenderer spriteRenderer;
    private final JLabel statusLabel;
    private final JLabel progressLabel;

    private final ChatPanel chatPanel;
    private final AdvicePanel advicePanel;
    private final SettingsPanel settingsPanel;

    private final JButton chatTabButton;
    private final JButton adviceTabButton;
    private final JButton settingsTabButton;

    private final CardLayout cardLayout;
    private final JPanel contentArea;

    private static final String TAB_CHAT = "Chat";
    private static final String TAB_ADVICE = "Advice";
    private static final String TAB_SETTINGS = "Settings";

    public LeaguesAiPanel(int animationSpeed) {
        super(false);
        setLayout(new BorderLayout(0, 0));
        setBackground(BACKGROUND_COLOR);

        // Top panel: sprite + status + progress
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(BACKGROUND_COLOR);
        topPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));

        spriteRenderer = new AsciiSpriteRenderer(animationSpeed);
        spriteRenderer.setAlignmentX(Component.CENTER_ALIGNMENT);
        topPanel.add(spriteRenderer);
        topPanel.add(Box.createVerticalStrut(4));

        statusLabel = new JLabel("Idle");
        statusLabel.setForeground(TEXT_COLOR);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        topPanel.add(statusLabel);

        progressLabel = new JLabel("0/0 tasks");
        progressLabel.setForeground(LABEL_COLOR);
        progressLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        progressLabel.setHorizontalAlignment(SwingConstants.CENTER);
        progressLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        topPanel.add(progressLabel);
        topPanel.add(Box.createVerticalStrut(4));

        add(topPanel, BorderLayout.NORTH);

        // Center: tab bar + card layout
        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.setBackground(BACKGROUND_COLOR);

        // Tab button bar
        JPanel tabBar = new JPanel(new GridLayout(1, 3, 2, 0));
        tabBar.setBackground(BACKGROUND_COLOR);
        tabBar.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));

        chatTabButton = createTabButton(TAB_CHAT);
        adviceTabButton = createTabButton(TAB_ADVICE);
        settingsTabButton = createTabButton(TAB_SETTINGS);

        tabBar.add(chatTabButton);
        tabBar.add(adviceTabButton);
        tabBar.add(settingsTabButton);

        centerPanel.add(tabBar, BorderLayout.NORTH);

        // Card layout content area
        cardLayout = new CardLayout();
        contentArea = new JPanel(cardLayout);
        contentArea.setBackground(BACKGROUND_COLOR);

        chatPanel = new ChatPanel();
        advicePanel = new AdvicePanel();
        settingsPanel = new SettingsPanel();

        contentArea.add(chatPanel, TAB_CHAT);
        contentArea.add(advicePanel, TAB_ADVICE);
        contentArea.add(settingsPanel, TAB_SETTINGS);

        centerPanel.add(contentArea, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        // Wire tab switching
        chatTabButton.addActionListener(e -> switchTab(TAB_CHAT));
        adviceTabButton.addActionListener(e -> switchTab(TAB_ADVICE));
        settingsTabButton.addActionListener(e -> switchTab(TAB_SETTINGS));

        // Start on Chat tab
        switchTab(TAB_CHAT);
    }

    private JButton createTabButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(TAB_INACTIVE_COLOR);
        button.setForeground(TAB_TEXT_COLOR);
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private void switchTab(String tabName) {
        cardLayout.show(contentArea, tabName);
        chatTabButton.setBackground(TAB_CHAT.equals(tabName) ? TAB_ACTIVE_COLOR : TAB_INACTIVE_COLOR);
        adviceTabButton.setBackground(TAB_ADVICE.equals(tabName) ? TAB_ACTIVE_COLOR : TAB_INACTIVE_COLOR);
        settingsTabButton.setBackground(TAB_SETTINGS.equals(tabName) ? TAB_ACTIVE_COLOR : TAB_INACTIVE_COLOR);
    }

    public AsciiSpriteRenderer getSpriteRenderer() {
        return spriteRenderer;
    }

    public ChatPanel getChatPanel() {
        return chatPanel;
    }

    public AdvicePanel getAdvicePanel() {
        return advicePanel;
    }

    public SettingsPanel getSettingsPanel() {
        return settingsPanel;
    }

    public void setStatus(String status) {
        statusLabel.setText(status == null ? "Idle" : status);
    }

    public void setProgress(int completed, int total) {
        progressLabel.setText(completed + "/" + total + " tasks");
    }
}
