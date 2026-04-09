package com.leaguesai.ui;

import com.leaguesai.data.GoalStore;
import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.TaskRepositoryImpl;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;
import org.junit.Before;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Verifies the {@link UnlockablesPanel} "Set as goal" callback contract and
 * the rebuild against empty / populated repositories. Kept lightweight on
 * purpose — this is a UI panel, not an overlay, so CLAUDE.md's non-negotiable
 * test rules (which require verifying draw calls for Quest-Helper-ported
 * overlays) do not apply here.
 */
public class UnlockablesPanelTest {

    private GoalStore goalStore;

    @Before
    public void setUp() throws Exception {
        // Non-existent file is fine — GoalStore starts fresh.
        goalStore = new GoalStore(new File(System.getProperty("java.io.tmpdir"),
                "unlockables-test-" + System.nanoTime() + ".json"));
    }

    private void runOnEdt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeAndWait(r);
        }
    }

    @Test
    public void constructs_withEmptyRepositoryWithoutThrowing() throws Exception {
        TaskRepository repo = new TaskRepositoryImpl(
                Collections.<Task>emptyList(),
                Collections.<Area>emptyList(),
                Collections.<Relic>emptyList(),
                Collections.<Pact>emptyList());

        AtomicReference<UnlockablesPanel> ref = new AtomicReference<>();
        runOnEdt(() -> ref.set(new UnlockablesPanel(repo, goalStore)));
        // Drain the rebuild task queued by the constructor.
        runOnEdt(() -> {});

        UnlockablesPanel panel = ref.get();
        assertNotNull(panel);
        // Three sections (Relics, Areas, Pacts) plus two struts = 5 children.
        assertTrue("Has at least 3 sections", panel.getComponentCount() >= 3);
    }

    @Test
    public void setAsGoal_firesCallbackWithPlanPrefix() throws Exception {
        // A single tier-6 relic so we can click its row and verify the phrase.
        Relic grimoire = Relic.builder()
                .id("grimoire")
                .name("Grimoire")
                .tier(6)
                .build();
        TaskRepository repo = new TaskRepositoryImpl(
                Collections.<Task>emptyList(),
                Collections.<Area>emptyList(),
                Collections.singletonList(grimoire),
                Collections.<Pact>emptyList());

        AtomicReference<UnlockablesPanel> ref = new AtomicReference<>();
        runOnEdt(() -> ref.set(new UnlockablesPanel(repo, goalStore)));
        runOnEdt(() -> {});
        UnlockablesPanel panel = ref.get();

        AtomicReference<String> captured = new AtomicReference<>();
        panel.setOnSetGoal(captured::set);

        // Invoke the row-build method directly and find the "Set as goal"
        // label's mouse listener. Going through the EDT click pipeline would
        // require the panel to be realised on screen.
        javax.swing.JPanel row = panel.makeRow(
                "Grimoire", "effect", false, "plan unlock the Grimoire relic");
        assertNotNull(row);

        // Walk the row looking for the "Set as goal" label and fire its click.
        javax.swing.JLabel link = findLabelByText(row, "Set as goal");
        assertNotNull("Set as goal link present", link);
        java.awt.event.MouseListener[] listeners = link.getMouseListeners();
        assertTrue("Has click listener", listeners.length > 0);
        listeners[0].mouseClicked(new java.awt.event.MouseEvent(
                link, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 0, 0, 1, false));

        assertEquals("plan unlock the Grimoire relic", captured.get());
    }

    @Test
    public void lockedBadge_reflectsGoalStore() throws Exception {
        Relic r = Relic.builder().id("grimoire").name("Grimoire").tier(6).build();
        TaskRepository repo = new TaskRepositoryImpl(
                Collections.<Task>emptyList(),
                Collections.<Area>emptyList(),
                Collections.singletonList(r),
                Collections.<Pact>emptyList());

        AtomicReference<UnlockablesPanel> ref = new AtomicReference<>();
        runOnEdt(() -> ref.set(new UnlockablesPanel(repo, goalStore)));
        runOnEdt(() -> {});
        UnlockablesPanel panel = ref.get();

        javax.swing.JPanel lockedRow = panel.makeRow("Grimoire", "effect", false, "goal");
        javax.swing.JLabel badge = findLabelByText(lockedRow, "locked");
        assertNotNull("Locked badge present when unlocked=false", badge);

        javax.swing.JPanel unlockedRow = panel.makeRow("Grimoire", "effect", true, "goal");
        javax.swing.JLabel badge2 = findLabelByText(unlockedRow, "unlocked");
        assertNotNull("Unlocked badge present when unlocked=true", badge2);
    }

    // ----- Phase 2 PR 3: pact select / respec flow ----------------------

    private UnlockablesPanel panelWithOnePact(Pact p) throws Exception {
        TaskRepository repo = new TaskRepositoryImpl(
                Collections.<Task>emptyList(),
                Collections.<Area>emptyList(),
                Collections.<Relic>emptyList(),
                Collections.singletonList(p));
        AtomicReference<UnlockablesPanel> ref = new AtomicReference<>();
        runOnEdt(() -> ref.set(new UnlockablesPanel(repo, goalStore)));
        runOnEdt(() -> {});
        return ref.get();
    }

    @Test
    public void pactRow_selectClickWritesToGoalStore() throws Exception {
        Pact aa = Pact.builder().id("pact-aa").name("AA").effect("Regen runes").build();
        UnlockablesPanel panel = panelWithOnePact(aa);

        javax.swing.JPanel row = panel.buildPactRow(aa);
        javax.swing.JLabel select = findLabelByText(row, "Select");
        assertNotNull("Select toggle present when budget has room", select);

        select.getMouseListeners()[0].mouseClicked(new java.awt.event.MouseEvent(
                select, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 0, 0, 1, false));

        assertTrue("Click must write to GoalStore", goalStore.isPactSelected("pact-aa"));
        assertEquals(1, goalStore.getSelectedPactCount());
    }

    @Test
    public void pactRow_selectedStateShowsChecked() throws Exception {
        Pact aa = Pact.builder().id("pact-aa").name("AA").effect("Regen runes").build();
        goalStore.selectPact("pact-aa");
        UnlockablesPanel panel = panelWithOnePact(aa);

        javax.swing.JPanel row = panel.buildPactRow(aa);
        javax.swing.JLabel selected = findLabelByText(row, "Selected \u2713");
        assertNotNull("Row should show 'Selected' state when pact is in GoalStore", selected);

        // Clicking Selected should deselect.
        selected.getMouseListeners()[0].mouseClicked(new java.awt.event.MouseEvent(
                selected, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 0, 0, 1, false));
        assertFalse("Click on Selected must deselect", goalStore.isPactSelected("pact-aa"));
    }

    @Test
    public void pactRow_budgetFullDisablesUnselectedRows() throws Exception {
        // Fill the budget.
        for (int i = 0; i < com.leaguesai.data.GoalStore.MAX_PACT_SLOTS; i++) {
            goalStore.selectPact("pact-filler-" + i);
        }
        assertFalse(goalStore.canSelectAnotherPact());

        Pact extra = Pact.builder().id("pact-extra").name("Extra").effect("").build();
        UnlockablesPanel panel = panelWithOnePact(extra);

        javax.swing.JPanel row = panel.buildPactRow(extra);
        // Disabled state renders as "budget full" label, no click listener.
        javax.swing.JLabel full = findLabelByText(row, "budget full");
        assertNotNull("Budget-full disabled state present", full);
        assertEquals("No click listener on disabled toggle",
                0, full.getMouseListeners().length);
    }

    @Test
    public void pactRow_parentPrereqDisablesChild() throws Exception {
        Pact child = Pact.builder()
                .id("pact-child")
                .name("Child")
                .effect("")
                .parentId("pact-parent")
                .build();
        UnlockablesPanel panel = panelWithOnePact(child);

        javax.swing.JPanel row = panel.buildPactRow(child);
        javax.swing.JLabel requires = findLabelByText(row, "requires parent");
        assertNotNull("Child pact with unmet parent shows disabled 'requires parent' state",
                requires);

        // After parent is selected, the child becomes selectable on the next build.
        goalStore.selectPact("pact-parent");
        javax.swing.JPanel row2 = panel.buildPactRow(child);
        javax.swing.JLabel select = findLabelByText(row2, "Select");
        assertNotNull("Child becomes selectable once parent is selected", select);
    }

    private javax.swing.JLabel findLabelByText(java.awt.Container container, String text) {
        for (java.awt.Component c : container.getComponents()) {
            if (c instanceof javax.swing.JLabel) {
                javax.swing.JLabel l = (javax.swing.JLabel) c;
                if (text.equals(l.getText())) return l;
            }
            if (c instanceof java.awt.Container) {
                javax.swing.JLabel deep = findLabelByText((java.awt.Container) c, text);
                if (deep != null) return deep;
            }
        }
        return null;
    }
}
