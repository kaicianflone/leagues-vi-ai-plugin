package com.leaguesai.ui;

import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
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
    private final GoalsPanel goalsPanel;
    private final SettingsPanel settingsPanel;

    private JButton chatTabButton;
    private JButton goalsTabButton;
    private JButton settingsTabButton;

    private CardLayout cardLayout;
    private JPanel contentArea;

    // Auth-gated card layout
    private final CardLayout centerCardLayout;
    private final JPanel centerContainer;
    private final JPanel preAuthPanel;
    private final JPanel mainContentPanel;
    private JButton preAuthSignInButton;
    private Runnable onPreAuthSignIn;
    private boolean authenticated = false;

    private static final String TAB_CHAT = "Chat";
    private static final String TAB_GOALS = "Goals";
    private static final String TAB_SETTINGS = "Settings";

    private static final String CARD_PRE_AUTH = "preAuth";
    private static final String CARD_MAIN = "main";

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

        // Tab panels — constructed first so main content can reference them
        chatPanel = new ChatPanel();
        goalsPanel = new GoalsPanel();
        settingsPanel = new SettingsPanel();

        // Build both cards (pre-auth + main)
        preAuthPanel = createPreAuthPanel();
        mainContentPanel = createMainContentPanel();

        centerCardLayout = new CardLayout();
        centerContainer = new JPanel(centerCardLayout);
        centerContainer.setBackground(BACKGROUND_COLOR);
        centerContainer.add(preAuthPanel, CARD_PRE_AUTH);
        centerContainer.add(mainContentPanel, CARD_MAIN);
        add(centerContainer, BorderLayout.CENTER);

        // Start on pre-auth
        centerCardLayout.show(centerContainer, CARD_PRE_AUTH);

        // Start on Chat tab within main
        switchTab(TAB_CHAT);
    }

    private JPanel createMainContentPanel() {
        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.setBackground(BACKGROUND_COLOR);

        // Tab button bar — only Chat + Goals visible post-auth.
        // Settings button is constructed for navigability but not added to the bar.
        JPanel tabBar = new JPanel(new GridLayout(1, 2, 2, 0));
        tabBar.setBackground(BACKGROUND_COLOR);
        tabBar.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));

        chatTabButton = createTabButton(TAB_CHAT);
        goalsTabButton = createTabButton(TAB_GOALS);
        settingsTabButton = createTabButton(TAB_SETTINGS);
        settingsTabButton.setVisible(false);

        tabBar.add(chatTabButton);
        tabBar.add(goalsTabButton);

        centerPanel.add(tabBar, BorderLayout.NORTH);

        // Card layout content area
        cardLayout = new CardLayout();
        contentArea = new JPanel(cardLayout);
        contentArea.setBackground(BACKGROUND_COLOR);

        contentArea.add(chatPanel, TAB_CHAT);
        contentArea.add(goalsPanel, TAB_GOALS);
        contentArea.add(settingsPanel, TAB_SETTINGS);

        centerPanel.add(contentArea, BorderLayout.CENTER);

        // Wire tab switching
        chatTabButton.addActionListener(e -> switchTab(TAB_CHAT));
        goalsTabButton.addActionListener(e -> switchTab(TAB_GOALS));
        settingsTabButton.addActionListener(e -> switchTab(TAB_SETTINGS));

        return centerPanel;
    }

    private JPanel createPreAuthPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BACKGROUND_COLOR);
        p.setBorder(new EmptyBorder(40, 20, 40, 20));

        JLabel title = new JLabel("Welcome to Leagues AI");
        title.setForeground(Color.WHITE);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("<html><div style='text-align:center;width:180px'>"
                + "Sign in with your ChatGPT account to get started.</div></html>");
        subtitle.setForeground(new Color(180, 180, 180));
        subtitle.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        preAuthSignInButton = new JButton("Sign in with ChatGPT");
        preAuthSignInButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        preAuthSignInButton.setMaximumSize(new Dimension(200, 32));
        preAuthSignInButton.addActionListener(e -> {
            if (onPreAuthSignIn != null) {
                preAuthSignInButton.setEnabled(false);
                preAuthSignInButton.setText("Opening Terminal...");
                onPreAuthSignIn.run();
            }
        });

        p.add(Box.createVerticalGlue());
        p.add(title);
        p.add(Box.createVerticalStrut(8));
        p.add(subtitle);
        p.add(Box.createVerticalStrut(20));
        p.add(preAuthSignInButton);
        p.add(Box.createVerticalGlue());
        return p;
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
        if (cardLayout == null || contentArea == null) return;
        cardLayout.show(contentArea, tabName);
        chatTabButton.setBackground(TAB_CHAT.equals(tabName) ? TAB_ACTIVE_COLOR : TAB_INACTIVE_COLOR);
        goalsTabButton.setBackground(TAB_GOALS.equals(tabName) ? TAB_ACTIVE_COLOR : TAB_INACTIVE_COLOR);
        settingsTabButton.setBackground(TAB_SETTINGS.equals(tabName) ? TAB_ACTIVE_COLOR : TAB_INACTIVE_COLOR);
    }

    /** Public hook for the chat→goals link button. EDT-safe. */
    public void switchToGoalsTab() {
        SwingUtilities.invokeLater(() -> switchTab(TAB_GOALS));
    }

    /** Public hook for the goals→chat link button. EDT-safe. */
    public void switchToChatTab() {
        SwingUtilities.invokeLater(() -> switchTab(TAB_CHAT));
    }

    public void setAuthenticated(boolean authed) {
        this.authenticated = authed;
        SwingUtilities.invokeLater(() -> {
            centerCardLayout.show(centerContainer, authed ? CARD_MAIN : CARD_PRE_AUTH);
            if (settingsTabButton != null) {
                settingsTabButton.setVisible(false);
            }
        });
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setOnPreAuthSignIn(Runnable handler) {
        this.onPreAuthSignIn = handler;
    }

    public void setPreAuthButtonText(String text) {
        SwingUtilities.invokeLater(() -> preAuthSignInButton.setText(text));
    }

    public void setPreAuthButtonEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> preAuthSignInButton.setEnabled(enabled));
    }

    public AsciiSpriteRenderer getSpriteRenderer() {
        return spriteRenderer;
    }

    public ChatPanel getChatPanel() {
        return chatPanel;
    }

    public GoalsPanel getGoalsPanel() {
        return goalsPanel;
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
