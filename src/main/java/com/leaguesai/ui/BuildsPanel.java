package com.leaguesai.ui;

import com.leaguesai.data.BuildStore;
import com.leaguesai.data.model.Build;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * BuildsPanel — card in the LeaguesAiPanel CardLayout for browsing / activating builds.
 *
 * <p>Accessed via "Browse Builds" button in GoalsPanel. Has its own "Back to Goals" link.
 * Not a tab — no tab button is added to the tab bar.
 */
public class BuildsPanel extends JPanel {

    private static final Color BACKGROUND_COLOR = new Color(30, 30, 30);
    private static final Color TEXT_COLOR        = new Color(220, 220, 220);
    private static final Color LABEL_COLOR       = new Color(160, 160, 160);
    private static final Color BORDER_COLOR      = new Color(55, 55, 55);
    private static final Color TAB_ACTIVE_COLOR  = new Color(60, 100, 160);
    private static final Color TAB_INACTIVE_COLOR = new Color(45, 45, 45);
    private static final Color CARD_BG_COLOR     = new Color(38, 38, 38);
    private static final Color WARN_COLOR        = new Color(220, 80, 60);
    private static final Color LINK_FG           = new Color(120, 170, 240);

    private static final int PACT_BUDGET = 40;

    // Callbacks
    private Runnable onBackToGoals;
    private Consumer<Build> onActivate;
    private Consumer<Build> onExport;
    private Runnable onImport;

    // Sub-tabs
    private JButton templatesTabButton;
    private JButton savedTabButton;
    private boolean showingTemplates = true;

    // Card list area
    private JPanel cardListPanel;
    private JScrollPane scrollPane;

    // Toast
    private JLabel toastLabel;
    private Timer toastTimer;

    // Internal executor for activate (avoid blocking EDT)
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Current data snapshot
    private List<Build> templateBuilds  = Collections.emptyList();
    private List<Build> savedBuilds     = Collections.emptyList();

    public BuildsPanel() {
        setLayout(new BorderLayout(0, 0));
        setBackground(BACKGROUND_COLOR);
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // ---- TOP: back link + sub-tab bar + import/export buttons ----
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(BACKGROUND_COLOR);

        // Back link row
        JButton backButton = new JButton("\u2190 Back to Goals");
        backButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        backButton.setBackground(new Color(55, 55, 55));
        backButton.setForeground(LINK_FG);
        backButton.setFocusPainted(false);
        backButton.setBorderPainted(false);
        backButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        backButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        backButton.addActionListener(e -> {
            if (onBackToGoals != null) onBackToGoals.run();
        });

        JPanel backRow = new JPanel(new BorderLayout());
        backRow.setBackground(BACKGROUND_COLOR);
        backRow.add(backButton, BorderLayout.WEST);
        backRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        backRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        // Sub-tab + action button row
        JPanel subTabRow = new JPanel(new BorderLayout(4, 0));
        subTabRow.setBackground(BACKGROUND_COLOR);
        subTabRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        subTabRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        subTabRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        // [Templates] [Saved]
        JPanel tabButtons = new JPanel(new GridLayout(1, 2, 2, 0));
        tabButtons.setBackground(BACKGROUND_COLOR);
        templatesTabButton = createSmallTabButton("Templates");
        savedTabButton     = createSmallTabButton("Saved");
        tabButtons.add(templatesTabButton);
        tabButtons.add(savedTabButton);

        // [Import] [Export(all)] — compact
        JPanel actionButtons = new JPanel(new GridLayout(1, 1, 2, 0));
        actionButtons.setBackground(BACKGROUND_COLOR);
        JButton importButton = createActionButton("Import");
        actionButtons.add(importButton);

        subTabRow.add(tabButtons, BorderLayout.WEST);
        subTabRow.add(actionButtons, BorderLayout.EAST);

        topPanel.add(backRow);
        topPanel.add(subTabRow);

        add(topPanel, BorderLayout.NORTH);

        // ---- CENTER: scrollable build card list ----
        cardListPanel = new JPanel();
        cardListPanel.setLayout(new BoxLayout(cardListPanel, BoxLayout.Y_AXIS));
        cardListPanel.setBackground(BACKGROUND_COLOR);

        scrollPane = new JScrollPane(cardListPanel);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(BACKGROUND_COLOR);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        add(scrollPane, BorderLayout.CENTER);

        // ---- BOTTOM: toast label ----
        toastLabel = new JLabel(" ");
        toastLabel.setForeground(new Color(150, 200, 150));
        toastLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        toastLabel.setBorder(BorderFactory.createEmptyBorder(4, 2, 2, 2));
        toastLabel.setVisible(false);
        add(toastLabel, BorderLayout.SOUTH);

        // Wire sub-tab switching
        templatesTabButton.addActionListener(e -> switchSubTab(true));
        savedTabButton.addActionListener(e     -> switchSubTab(false));
        importButton.addActionListener(e -> {
            if (onImport != null) onImport.run();
        });

        // Start on Templates
        switchSubTab(true);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Called when user clicks "Back to Goals". */
    public void setOnBackToGoals(Runnable r) {
        this.onBackToGoals = r;
    }

    /** Called on background thread when user clicks Activate on a build card. */
    public void setOnActivate(Consumer<Build> callback) {
        this.onActivate = callback;
    }

    /** Called when user clicks Export on a build card. */
    public void setOnExport(Consumer<Build> callback) {
        this.onExport = callback;
    }

    /** Called when user clicks Import button. */
    public void setOnImport(Runnable r) {
        this.onImport = r;
    }

    /** Shut down the internal executor. Call from plugin shutDown(). */
    public void shutdown() {
        executor.shutdownNow();
    }

    /** Reload the build list from BuildStore. Call after import/save. */
    public void refreshBuilds(BuildStore buildStore) {
        if (buildStore == null) {
            templateBuilds = Collections.emptyList();
            savedBuilds    = Collections.emptyList();
        } else {
            templateBuilds = buildStore.listSeeds();
            savedBuilds    = buildStore.listSaved();
        }
        SwingUtilities.invokeLater(this::rebuildCardList);
    }

    /** Show a toast message at the bottom (3-second auto-dismiss). */
    public void showToast(String message) {
        SwingUtilities.invokeLater(() -> {
            toastLabel.setText(message);
            toastLabel.setVisible(true);
            if (toastTimer != null && toastTimer.isRunning()) {
                toastTimer.stop();
            }
            toastTimer = new Timer(3000, e -> {
                toastLabel.setVisible(false);
                toastLabel.setText(" ");
            });
            toastTimer.setRepeats(false);
            toastTimer.start();
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void switchSubTab(boolean templates) {
        showingTemplates = templates;
        templatesTabButton.setBackground(templates ? TAB_ACTIVE_COLOR : TAB_INACTIVE_COLOR);
        savedTabButton.setBackground(!templates ? TAB_ACTIVE_COLOR : TAB_INACTIVE_COLOR);
        rebuildCardList();
    }

    private void rebuildCardList() {
        cardListPanel.removeAll();

        List<Build> builds = showingTemplates ? templateBuilds : savedBuilds;

        if (builds.isEmpty()) {
            JLabel empty = new JLabel(showingTemplates
                    ? "<html><div style='text-align:center;width:170px;color:#888'>No template builds found.</div></html>"
                    : "<html><div style='text-align:center;width:170px;color:#888'>No saved builds yet.<br>Activate a template or save current.</div></html>");
            empty.setHorizontalAlignment(JLabel.CENTER);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            cardListPanel.add(Box.createVerticalStrut(16));
            cardListPanel.add(empty);
        } else {
            for (Build build : builds) {
                cardListPanel.add(createBuildCard(build));
                cardListPanel.add(Box.createVerticalStrut(4));
            }
        }

        cardListPanel.revalidate();
        cardListPanel.repaint();
    }

    private JPanel createBuildCard(Build build) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BG_COLOR);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                new EmptyBorder(6, 8, 6, 8)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // Row 1: name + author + version badge + Activate + Export
        JPanel row1 = new JPanel(new BorderLayout(4, 0));
        row1.setBackground(CARD_BG_COLOR);
        row1.setAlignmentX(Component.LEFT_ALIGNMENT);
        row1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JLabel nameLabel = new JLabel(build.getName() != null ? build.getName() : "(no name)");
        nameLabel.setForeground(TEXT_COLOR);
        nameLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));

        String author  = build.getAuthor() != null ? "@" + build.getAuthor() : "";
        int    version = build.getVersion();
        JLabel metaLabel = new JLabel(author + (version > 0 ? "  v" + version : ""));
        metaLabel.setForeground(LABEL_COLOR);
        metaLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));

        JButton activateButton = createActionButton("Activate");
        JButton exportButton   = createActionButton("Export");

        activateButton.addActionListener(e -> {
            activateButton.setEnabled(false);
            activateButton.setText("Loading...");
            executor.submit(() -> {
                try {
                    if (onActivate != null) onActivate.accept(build);
                } catch (Exception ex) {
                    // swallow — plugin-level error handling via activateBuild()
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        activateButton.setEnabled(true);
                        activateButton.setText("Activate");
                    });
                }
            });
        });

        exportButton.addActionListener(e -> {
            if (onExport != null) onExport.accept(build);
        });

        JPanel nameCol = new JPanel();
        nameCol.setLayout(new BoxLayout(nameCol, BoxLayout.X_AXIS));
        nameCol.setBackground(CARD_BG_COLOR);
        nameCol.add(nameLabel);
        nameCol.add(Box.createHorizontalStrut(6));
        nameCol.add(metaLabel);

        JPanel btnCol = new JPanel();
        btnCol.setLayout(new BoxLayout(btnCol, BoxLayout.X_AXIS));
        btnCol.setBackground(CARD_BG_COLOR);
        btnCol.add(activateButton);
        btnCol.add(Box.createHorizontalStrut(4));
        btnCol.add(exportButton);

        row1.add(nameCol, BorderLayout.WEST);
        row1.add(btnCol, BorderLayout.EAST);

        // Row 2: relic / area / pact counts
        int relicCount = build.getRelicIds() != null ? build.getRelicIds().size() : 0;
        int areaCount  = build.getAreaIds()  != null ? build.getAreaIds().size()  : 0;
        int pactCount  = build.getPactIds()  != null ? build.getPactIds().size()  : 0;
        boolean pactOver = pactCount > PACT_BUDGET;

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row2.setBackground(CARD_BG_COLOR);
        row2.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel countsLabel = new JLabel("Relics: " + relicCount + " | Areas: " + areaCount + " | Pacts: ");
        countsLabel.setForeground(LABEL_COLOR);
        countsLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));

        JLabel pactLabel = new JLabel(pactCount + "/" + PACT_BUDGET);
        pactLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        if (pactOver) {
            pactLabel.setForeground(WARN_COLOR);
            pactLabel.setText(pactCount + "/" + PACT_BUDGET + " \u26A0");
        } else {
            pactLabel.setForeground(LABEL_COLOR);
        }

        row2.add(countsLabel);
        row2.add(pactLabel);

        // Row 3: skill targets (optional)
        Map<String, Integer> skills = build.getTargetSkills();
        JPanel row3 = null;
        if (skills != null && !skills.isEmpty()) {
            StringBuilder sb = new StringBuilder("Skills: ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : skills.entrySet()) {
                if (!first) sb.append("  ");
                sb.append(e.getKey()).append(" ").append(e.getValue());
                first = false;
            }
            JLabel skillLabel = new JLabel(sb.toString());
            skillLabel.setForeground(LABEL_COLOR);
            skillLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            skillLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

            row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row3.setBackground(CARD_BG_COLOR);
            row3.setAlignmentX(Component.LEFT_ALIGNMENT);
            row3.add(skillLabel);
        }

        card.add(row1);
        card.add(Box.createVerticalStrut(2));
        card.add(row2);
        if (row3 != null) {
            card.add(row3);
        }

        return card;
    }

    private JButton createSmallTabButton(String text) {
        JButton btn = new JButton(text);
        btn.setBackground(TAB_INACTIVE_COLOR);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMargin(new Insets(2, 6, 2, 6));
        return btn;
    }

    private JButton createActionButton(String text) {
        JButton btn = new JButton(text);
        btn.setBackground(new Color(50, 55, 65));
        btn.setForeground(TEXT_COLOR);
        btn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMargin(new Insets(2, 6, 2, 6));
        return btn;
    }
}
