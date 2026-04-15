package com.leaguesai.ui;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Collapsible task browser with area / difficulty / pact-only filters.
 * Renders a lazy-paged list of task cards (50 per page) inside a scroll column.
 * Sits below the GoalAccordion inside GoalsPanel's scroll pane.
 */
public class TaskBrowserPanel extends JPanel {

    private static final Color BG           = new Color(30, 30, 30);
    private static final Color SECTION_BG   = new Color(38, 38, 38);
    private static final Color BORDER_COLOR = new Color(60, 60, 60);
    private static final Color TEXT_FG      = new Color(220, 220, 220);
    private static final Color DIM_FG       = new Color(130, 130, 130);
    private static final Color PACT_BADGE   = new Color(100, 60, 140);
    private static final Color PACT_FG      = new Color(210, 170, 255);

    private static final Color[] DIFF_BG = {
        new Color(55, 70, 55),   // EASY
        new Color(50, 60, 80),   // MEDIUM
        new Color(80, 60, 40),   // HARD
        new Color(80, 55, 55),   // ELITE
        new Color(70, 55, 80),   // MASTER
    };
    private static final Color[] DIFF_FG = {
        new Color(140, 210, 130),
        new Color(130, 170, 240),
        new Color(240, 180, 100),
        new Color(240, 110, 110),
        new Color(200, 140, 240),
    };

    private static final int PAGE_SIZE = 50;

    private static final String[] AREA_VALUES = {
        "all", "asgarnia", "desert", "fremennik", "general",
        "kandarin", "karamja", "kourend", "morytania",
        "tirannwn", "varlamore", "wilderness"
    };
    private static final String[] AREA_LABELS = {
        "All Areas", "Asgarnia", "Desert", "Fremennik", "General",
        "Kandarin", "Karamja", "Kourend", "Morytania",
        "Tirannwn", "Varlamore", "Wilderness"
    };

    private final TaskRepository repo;

    // Expand / collapse
    private boolean expanded = false;
    private final JButton toggleButton;
    private final JPanel contentPanel;

    // Filters
    private final JComboBox<String> areaCombo;
    private final Map<Difficulty, JToggleButton> diffButtons = new LinkedHashMap<>();
    private final JCheckBox pactOnlyBox;

    // Task list area
    private final JPanel taskListPanel;
    private int currentOffset = 0;
    private final JButton loadMoreButton;
    private final JLabel countLabel;

    // Current filter state
    private String selectedArea = null;       // null = all
    private Set<Difficulty> selectedDiffs;    // empty = all
    private boolean pactOnly = false;

    public TaskBrowserPanel(TaskRepository repo) {
        this.repo = repo;
        // Mirror the 4 buttons shown in the filter bar — MASTER has no button so exclude it
        // from the initial set so the initial state is consistent with post-click state.
        selectedDiffs = new HashSet<>(Arrays.asList(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD, Difficulty.ELITE));

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(BG);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        // ---- Toggle header ----
        toggleButton = new JButton("Browse Tasks \u25BC");
        toggleButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        toggleButton.setBackground(new Color(45, 45, 45));
        toggleButton.setForeground(new Color(160, 190, 230));
        toggleButton.setFocusPainted(false);
        toggleButton.setBorderPainted(false);
        toggleButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggleButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        toggleButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        toggleButton.addActionListener(e -> setExpanded(!expanded));
        add(toggleButton);

        // ---- Content panel (hidden when collapsed) ----
        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(SECTION_BG);
        contentPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.setVisible(false);
        add(contentPanel);

        // ---- Filter bar ----
        JPanel filterPanel = new JPanel();
        filterPanel.setLayout(new BoxLayout(filterPanel, BoxLayout.Y_AXIS));
        filterPanel.setBackground(SECTION_BG);
        filterPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Area row
        areaCombo = new JComboBox<>(AREA_LABELS);
        areaCombo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        areaCombo.setBackground(new Color(50, 50, 50));
        areaCombo.setForeground(TEXT_FG);
        areaCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        areaCombo.addActionListener(e -> onFilterChanged());

        JLabel areaLabel = new JLabel("Area:");
        areaLabel.setForeground(DIM_FG);
        areaLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        JPanel areaRow = new JPanel(new BorderLayout(4, 0));
        areaRow.setBackground(SECTION_BG);
        areaRow.add(areaLabel, BorderLayout.WEST);
        areaRow.add(areaCombo, BorderLayout.CENTER);
        areaRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        areaRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        filterPanel.add(areaRow);
        filterPanel.add(Box.createVerticalStrut(4));

        // Difficulty toggle buttons
        JPanel diffRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        diffRow.setBackground(SECTION_BG);
        diffRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        Difficulty[] diffs = { Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD, Difficulty.ELITE };
        for (Difficulty d : diffs) {
            int idx = d.getTier() - 1;
            JToggleButton btn = new JToggleButton(capitalize(d.name()));
            btn.setSelected(true);
            btn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            btn.setBackground(DIFF_BG[idx]);
            btn.setForeground(DIFF_FG[idx]);
            btn.setFocusPainted(false);
            btn.setBorderPainted(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.setPreferredSize(new Dimension(44, 18));
            btn.addActionListener(e -> onFilterChanged());
            diffButtons.put(d, btn);
            diffRow.add(btn);
        }
        diffRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        filterPanel.add(diffRow);
        filterPanel.add(Box.createVerticalStrut(4));

        // Pact-only checkbox
        pactOnlyBox = new JCheckBox("Pact tasks only");
        pactOnlyBox.setForeground(PACT_FG);
        pactOnlyBox.setBackground(SECTION_BG);
        pactOnlyBox.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        pactOnlyBox.setFocusPainted(false);
        pactOnlyBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        pactOnlyBox.addActionListener(e -> onFilterChanged());
        filterPanel.add(pactOnlyBox);
        filterPanel.add(Box.createVerticalStrut(4));

        contentPanel.add(filterPanel);
        contentPanel.add(Box.createVerticalStrut(4));

        // ---- Count label ----
        countLabel = new JLabel(" ");
        countLabel.setForeground(DIM_FG);
        countLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        countLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(countLabel);
        contentPanel.add(Box.createVerticalStrut(2));

        // ---- Task list ----
        taskListPanel = new JPanel();
        taskListPanel.setLayout(new BoxLayout(taskListPanel, BoxLayout.Y_AXIS));
        taskListPanel.setBackground(SECTION_BG);
        taskListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(taskListPanel);

        // ---- Load more button ----
        loadMoreButton = new JButton("Load more...");
        loadMoreButton.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        loadMoreButton.setBackground(new Color(45, 45, 55));
        loadMoreButton.setForeground(new Color(160, 190, 230));
        loadMoreButton.setFocusPainted(false);
        loadMoreButton.setBorderPainted(false);
        loadMoreButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        loadMoreButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        loadMoreButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        loadMoreButton.setVisible(false);
        loadMoreButton.addActionListener(e -> loadNextPage());
        contentPanel.add(loadMoreButton);
    }

    private void setExpanded(boolean expand) {
        expanded = expand;
        toggleButton.setText(expand ? "Browse Tasks \u25B2" : "Browse Tasks \u25BC");
        contentPanel.setVisible(expand);
        if (expand && taskListPanel.getComponentCount() == 0) {
            refreshTasks();
        }
        revalidate();
        repaint();
    }

    private void onFilterChanged() {
        int areaIdx = areaCombo.getSelectedIndex();
        selectedArea = (areaIdx <= 0) ? null : AREA_VALUES[areaIdx];

        selectedDiffs = new HashSet<>();
        for (Map.Entry<Difficulty, JToggleButton> e : diffButtons.entrySet()) {
            if (e.getValue().isSelected()) selectedDiffs.add(e.getKey());
        }

        pactOnly = pactOnlyBox.isSelected();
        refreshTasks();
    }

    private void refreshTasks() {
        currentOffset = 0;
        taskListPanel.removeAll();
        loadNextPage();
    }

    private void loadNextPage() {
        Set<Difficulty> diffs = selectedDiffs.isEmpty() ? null : selectedDiffs;
        List<Task> page = repo.findFiltered(selectedArea, diffs, pactOnly, currentOffset, PAGE_SIZE);
        for (Task t : page) {
            taskListPanel.add(buildTaskCard(t));
            taskListPanel.add(Box.createVerticalStrut(2));
        }
        currentOffset += page.size();

        // Update count label on first page
        if (currentOffset <= PAGE_SIZE) {
            // Get total count with no limit — quick since it's in-memory
            int total = repo.findFiltered(selectedArea, diffs, pactOnly, 0, Integer.MAX_VALUE).size();
            countLabel.setText(total + " task" + (total == 1 ? "" : "s"));
        }

        boolean hasMore = page.size() == PAGE_SIZE;
        loadMoreButton.setVisible(hasMore);
        taskListPanel.revalidate();
        taskListPanel.repaint();
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    private JPanel buildTaskCard(Task task) {
        JPanel card = new JPanel(new BorderLayout(4, 2));
        card.setBackground(new Color(42, 42, 42));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));

        // Name
        JLabel nameLabel = new JLabel(task.getName());
        nameLabel.setForeground(TEXT_FG);
        nameLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        card.add(nameLabel, BorderLayout.CENTER);

        // Badge row: difficulty + area + points + pact
        JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        badges.setBackground(new Color(42, 42, 42));

        if (task.getDifficulty() != null) {
            int idx = task.getDifficulty().getTier() - 1;
            JLabel diff = badge(capitalize(task.getDifficulty().name()), DIFF_BG[idx], DIFF_FG[idx]);
            badges.add(diff);
        }
        if (task.getArea() != null && !task.getArea().isEmpty()) {
            badges.add(badge(capitalize(task.getArea()), new Color(45, 50, 60), new Color(160, 185, 220)));
        }
        if (task.getPoints() > 0) {
            badges.add(badge(task.getPoints() + " pts", new Color(50, 45, 35), new Color(215, 185, 110)));
        }
        if ("pact".equalsIgnoreCase(task.getCategory())) {
            badges.add(badge("Pact", PACT_BADGE, PACT_FG));
        }

        card.add(badges, BorderLayout.SOUTH);
        return card;
    }

    private static JLabel badge(String text, Color bg, Color fg) {
        JLabel lbl = new JLabel(text);
        lbl.setOpaque(true);
        lbl.setBackground(bg);
        lbl.setForeground(fg);
        lbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
        lbl.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
        return lbl;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
