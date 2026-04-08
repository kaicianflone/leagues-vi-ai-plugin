package com.leaguesai.ui;

import com.leaguesai.data.GoalStore;
import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Goal picker rendered at the top of {@link GoalsPanel}. Three collapsible
 * sections: Relics (grouped by tier), Areas (Universal vs Unlockable split),
 * and Demonic Pacts (flat list, phase 1).
 *
 * <p>Clicking "Set as goal" on any row fires {@link #setOnSetGoal(Consumer)}
 * with a canonical goal phrase like {@code "plan unlock the Grimoire relic"}
 * that {@code ChatService.maybeTriggerPlanner} recognises via its "plan "
 * prefix trigger.
 *
 * <p>Locked / unlocked state is sourced from {@link GoalStore#isUnlocked(String)}
 * which is always false in phase 1 — phase 2 will hook it to in-game events.
 *
 * <p>Follows the same lightweight nested {@code JPanel} + click-to-toggle
 * pattern as {@link GoalAccordion}. No animation, no shared abstraction —
 * the two accordions don't have enough overlap to justify a generic row
 * class.
 */
public class UnlockablesPanel extends JPanel {

    // Colour palette matches GoalAccordion so the two blocks blend together.
    private static final Color SECTION_BG = new Color(42, 42, 42);
    private static final Color SECTION_BG_HOVER = new Color(52, 52, 52);
    private static final Color CHILD_BG = new Color(36, 36, 36);
    private static final Color ROW_BG = new Color(48, 48, 48);
    private static final Color HEADER_FG = new Color(220, 220, 220);
    private static final Color META_FG = new Color(150, 150, 150);
    private static final Color TIER_FG = new Color(255, 200, 80);
    private static final Color LOCKED_FG = new Color(200, 100, 100);
    private static final Color UNLOCKED_FG = new Color(120, 200, 120);
    private static final Color BUTTON_FG = new Color(120, 170, 240);
    private static final Color BORDER = new Color(60, 60, 60);

    // Wiki editorial split — Varlamore and Karamja are the two universal
    // areas, everything else is unlockable. Kept as a constant so future
    // scraper runs don't accidentally change the grouping.
    private static final Set<String> UNIVERSAL_AREA_IDS =
            new HashSet<>(Arrays.asList("Varlamore", "Karamja"));

    private final TaskRepository repo;
    private final GoalStore goalStore;

    private Consumer<String> onSetGoal;

    public UnlockablesPanel(TaskRepository repo, GoalStore goalStore) {
        this.repo = repo;
        this.goalStore = goalStore;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(30, 30, 30));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        rebuild();
    }

    /**
     * Set the callback fired when the user clicks "Set as goal" on any row.
     * The string argument is a planner-ready phrase starting with "plan ".
     */
    public void setOnSetGoal(Consumer<String> callback) {
        this.onSetGoal = callback;
    }

    /**
     * Rebuild all three sections from the repo. Called once at construction;
     * can be called again after the scraper has been re-run to refresh the
     * view without restarting the plugin.
     */
    public void rebuild() {
        SwingUtilities.invokeLater(() -> {
            removeAll();
            JPanel relicsSection = buildRelicsSection();
            JPanel areasSection = buildAreasSection();
            JPanel pactsSection = buildPactsSection();

            // Every child in a BoxLayout.Y_AXIS parent must have LEFT
            // alignmentX, otherwise Swing centers rows against the widest
            // sibling and content appears shifted right inside the panel.
            relicsSection.setAlignmentX(Component.LEFT_ALIGNMENT);
            areasSection.setAlignmentX(Component.LEFT_ALIGNMENT);
            pactsSection.setAlignmentX(Component.LEFT_ALIGNMENT);

            add(relicsSection);
            add(leftAlignedStrut(4));
            add(areasSection);
            add(leftAlignedStrut(4));
            add(pactsSection);
            revalidate();
            repaint();
        });
    }

    /**
     * {@code Box.createVerticalStrut} returns a Filler whose alignmentX is
     * 0.5 (CENTER). In a LEFT-aligned BoxLayout.Y_AXIS column the centered
     * strut forces its row siblings to offset. Wrap in a LEFT-aligned
     * Filler so the whole column stays flush left.
     */
    private static Component leftAlignedStrut(int h) {
        Box.Filler f = (Box.Filler) Box.createRigidArea(new Dimension(0, h));
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
        return f;
    }

    // -------------------------------------------------------------------------
    // Sections
    // -------------------------------------------------------------------------

    private JPanel buildRelicsSection() {
        List<Relic> relics = repo != null ? repo.getAllRelics() : java.util.Collections.emptyList();

        // TreeMap so tiers sort naturally 1..8 even if the scraper inserted
        // them out of order.
        Map<Integer, JPanel> tierGroups = new TreeMap<>();
        for (Relic r : relics) {
            JPanel grp = tierGroups.computeIfAbsent(r.getTier(), t -> newGroupPanel("Tier " + t));
            grp.add(buildRelicRow(r));
        }

        JPanel child = newChildColumn();
        for (JPanel grp : tierGroups.values()) {
            child.add(grp);
            child.add(leftAlignedStrut(2));
        }
        if (tierGroups.isEmpty()) {
            child.add(emptyLabel("No relics loaded — run scraper."));
        }

        return buildCollapsible("Relics (" + relics.size() + ")", child);
    }

    private JPanel buildAreasSection() {
        List<Area> areas = repo != null ? repo.getAllAreas() : java.util.Collections.emptyList();

        // LinkedHashMap preserves declared order within each group.
        Map<String, JPanel> groups = new LinkedHashMap<>();
        groups.put("Universal", newGroupPanel("Universal"));
        groups.put("Unlockable", newGroupPanel("Unlockable"));

        int universalCount = 0;
        int unlockableCount = 0;
        for (Area a : areas) {
            String bucket = UNIVERSAL_AREA_IDS.contains(a.getId()) ? "Universal" : "Unlockable";
            groups.get(bucket).add(buildAreaRow(a));
            if ("Universal".equals(bucket)) universalCount++;
            else unlockableCount++;
        }

        JPanel child = newChildColumn();
        for (JPanel grp : groups.values()) {
            child.add(grp);
            child.add(leftAlignedStrut(2));
        }
        if (areas.isEmpty()) {
            child.add(emptyLabel("No areas loaded — run scraper."));
        }

        return buildCollapsible(
                "Areas (" + universalCount + " universal / " + unlockableCount + " unlockable)",
                child);
    }

    private JPanel buildPactsSection() {
        List<Pact> pacts = repo != null ? repo.getAllPacts() : java.util.Collections.emptyList();

        JPanel child = newChildColumn();
        for (Pact p : pacts) {
            child.add(buildPactRow(p));
        }
        if (pacts.isEmpty()) {
            child.add(emptyLabel("No pacts loaded — run scraper."));
        }

        return buildCollapsible("Demonic Pacts (" + pacts.size() + ")", child);
    }

    /**
     * Standard child column used inside every collapsible section: full
     * width, LEFT-aligned, BoxLayout Y_AXIS. Every row added to this
     * container must also be LEFT-aligned or Swing will offset it.
     */
    private JPanel newChildColumn() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(CHILD_BG);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return p;
    }

    // -------------------------------------------------------------------------
    // Row builders
    // -------------------------------------------------------------------------

    private JPanel buildRelicRow(Relic r) {
        String id = r.getId();
        boolean unlocked = goalStore != null && goalStore.isUnlocked(id);
        String goalPhrase = "plan unlock the " + r.getName() + " relic";
        // Effect text can be hundreds of characters — let HTML wrap it
        // instead of truncating. No data hidden from the user.
        String effect = r.getEffects() != null ? r.getEffects().toString() : "";
        return makeRow(r.getName(), effect, unlocked, goalPhrase);
    }

    private JPanel buildAreaRow(Area a) {
        String id = a.getId();
        boolean unlocked = goalStore != null && goalStore.isUnlocked(id);
        String costMeta = a.getUnlockCost() > 0 ? a.getUnlockCost() + " pts" : "cost TBD";
        String goalPhrase = "plan unlock " + a.getName();
        return makeRow(a.getName(), costMeta, unlocked, goalPhrase);
    }

    private JPanel buildPactRow(Pact p) {
        String id = p.getId();
        boolean unlocked = goalStore != null && goalStore.isUnlocked(id);
        String label = "Pact " + p.getName();
        String goalPhrase = "plan unlock pact " + p.getName();
        // Full effect text — wraps in HTML.
        return makeRow(label, p.getEffect() != null ? p.getEffect() : "", unlocked, goalPhrase);
    }

    /**
     * Build one data row: name + meta line + lock badge + "Set as goal" link.
     * Kept package-private so tests can invoke it directly.
     */
    JPanel makeRow(String title, String meta, boolean unlocked, String goalPhrase) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(ROW_BG);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Width: fill column (MAX_VALUE). Height: no cap — BoxLayout picks
        // the row's preferredSize, which reflows as HTML text wraps across
        // lines. Without removing the old 44px cap the label was clipped.
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // Right column: lock badge + "Set as goal" link, stacked. Fixed
        // width so CENTER has a predictable remainder to wrap into.
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setOpaque(false);
        right.setPreferredSize(new Dimension(60, 30));

        JLabel lockBadge = new JLabel(unlocked ? "unlocked" : "locked", SwingConstants.RIGHT);
        lockBadge.setForeground(unlocked ? UNLOCKED_FG : LOCKED_FG);
        lockBadge.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
        lockBadge.setAlignmentX(Component.RIGHT_ALIGNMENT);

        JLabel setGoalLink = new JLabel("Set as goal", SwingConstants.RIGHT);
        setGoalLink.setForeground(BUTTON_FG);
        setGoalLink.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        setGoalLink.setAlignmentX(Component.RIGHT_ALIGNMENT);
        setGoalLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setGoalLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (onSetGoal != null) onSetGoal.accept(goalPhrase);
            }
        });

        right.add(lockBadge);
        right.add(setGoalLink);

        // Left column: HTML-wrapped title + meta. Width hint (~130px) is
        // the side panel width (~210px) minus the right column (60px) and
        // the row's 12px horizontal padding — forces JLabel to wrap long
        // text across multiple lines instead of clipping on the right.
        String safeTitle = escapeHtml(title);
        String safeMeta = escapeHtml(meta);
        JLabel textLabel = new JLabel(
                "<html><div style='width:130px'>"
                        + "<b style='color:#DCDCDC'>" + safeTitle + "</b>"
                        + (safeMeta.isEmpty()
                                ? ""
                                : "<br><span style='color:#969696;font-size:9px'>" + safeMeta + "</span>")
                        + "</div></html>");
        textLabel.setForeground(HEADER_FG);
        textLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        textLabel.setVerticalAlignment(SwingConstants.TOP);

        row.add(textLabel, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // -------------------------------------------------------------------------
    // Collapsible helpers
    // -------------------------------------------------------------------------

    private JPanel buildCollapsible(String title, JPanel child) {
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setBackground(SECTION_BG);
        wrapper.setBorder(BorderFactory.createLineBorder(BORDER));
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Stretch to parent column width — without this BoxLayout shrinks
        // the wrapper to its widest child and content ends up indented.
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setBackground(SECTION_BG);
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        final JLabel chevron = new JLabel("\u25B6");
        chevron.setForeground(META_FG);
        chevron.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(HEADER_FG);
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.setOpaque(false);
        left.add(chevron);
        left.add(Box.createHorizontalStrut(6));
        left.add(titleLabel);

        header.add(left, BorderLayout.WEST);
        child.setVisible(false);

        wrapper.add(header);
        wrapper.add(child);

        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                boolean now = !child.isVisible();
                child.setVisible(now);
                chevron.setText(now ? "\u25BC" : "\u25B6");
                wrapper.revalidate();
                wrapper.repaint();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                header.setBackground(SECTION_BG_HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                header.setBackground(SECTION_BG);
            }
        });

        return wrapper;
    }

    private JPanel newGroupPanel(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(CHILD_BG);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Let the group stretch to the full width of the parent column so
        // rows inside it can render flush-left against the panel edge.
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel h = new JLabel(title);
        h.setForeground(TIER_FG);
        h.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        h.setAlignmentX(Component.LEFT_ALIGNMENT);
        h.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
        p.add(h);
        return p;
    }

    private JLabel emptyLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(META_FG);
        l.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 10));
        l.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

}
