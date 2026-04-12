package com.leaguesai.ui;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Compile-check + minimal headless smoke tests for {@link BuildsPanel}.
 *
 * <p>BuildsPanel is a Swing component; full interaction testing would require
 * a real display. These tests verify that the panel constructs without
 * exception and that its public API contracts are reachable headlessly.
 */
public class BuildsPanelTest {

    private void runOnEdt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeAndWait(r);
        }
    }

    @Test
    public void builds_panel_constructs_without_exception() throws Exception {
        System.setProperty("java.awt.headless", "true");
        AtomicReference<BuildsPanel> ref = new AtomicReference<>();
        runOnEdt(() -> ref.set(new BuildsPanel()));
        assertNotNull(ref.get());
    }

    @Test
    public void builds_panel_callbacks_set_without_exception() throws Exception {
        System.setProperty("java.awt.headless", "true");
        AtomicReference<BuildsPanel> ref = new AtomicReference<>();
        runOnEdt(() -> {
            BuildsPanel panel = new BuildsPanel();
            panel.setOnBackToGoals(() -> {});
            panel.setOnActivate(b -> {});
            panel.setOnExport(b -> {});
            panel.setOnImport(() -> {});
            ref.set(panel);
        });
        assertNotNull(ref.get());
    }

    @Test
    public void builds_panel_refresh_with_null_store_does_not_throw() throws Exception {
        System.setProperty("java.awt.headless", "true");
        AtomicReference<BuildsPanel> ref = new AtomicReference<>();
        runOnEdt(() -> ref.set(new BuildsPanel()));
        // refreshBuilds posts to EDT internally, so call it and then drain EDT
        ref.get().refreshBuilds(null);
        runOnEdt(() -> {}); // drain
        assertNotNull(ref.get());
    }

    @Test
    public void builds_panel_show_toast_does_not_throw() throws Exception {
        System.setProperty("java.awt.headless", "true");
        AtomicReference<BuildsPanel> ref = new AtomicReference<>();
        runOnEdt(() -> ref.set(new BuildsPanel()));
        ref.get().showToast("Test toast");
        runOnEdt(() -> {}); // drain
        assertNotNull(ref.get());
    }
}
