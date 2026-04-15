package com.leaguesai.core.monitors;

import com.leaguesai.data.GoalStore;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.*;

public class LeagueStatusMonitorTest {

    @Mock private Client client;
    @Mock private EventBus eventBus;
    @Mock private GoalStore goalStore;

    private LeagueStatusMonitor monitor;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        // Default: all varbits return 0 (no unlock)
        when(client.getVarbitValue(anyInt())).thenReturn(0);
        monitor = new LeagueStatusMonitor(client, eventBus);
        monitor.setGoalStore(goalStore);
    }

    // ------------------------------------------------------------------
    // initialize() — reads current state on startup
    // ------------------------------------------------------------------

    @Test
    public void testInitialize_noUnlocks_doesNotCallMarkUnlocked() {
        monitor.initialize();
        verify(goalStore, never()).markUnlocked(anyString());
    }

    @Test
    public void testInitialize_areaUnlocked_callsMarkUnlocked() {
        // LEAGUE_AREA_SELECTION_0 = 2 → "desert"
        when(client.getVarbitValue(VarbitID.LEAGUE_AREA_SELECTION_0)).thenReturn(2);
        monitor.initialize();
        verify(goalStore, atLeastOnce()).markUnlocked("desert");
    }

    @Test
    public void testInitialize_multipleAreasUnlocked() {
        when(client.getVarbitValue(VarbitID.LEAGUE_AREA_SELECTION_0)).thenReturn(1); // asgarnia
        when(client.getVarbitValue(VarbitID.LEAGUE_AREA_SELECTION_1)).thenReturn(6); // kourend
        monitor.initialize();
        verify(goalStore).markUnlocked("asgarnia");
        verify(goalStore).markUnlocked("kourend");
    }

    // ------------------------------------------------------------------
    // onVarbitChanged — fires on live unlock events
    // ------------------------------------------------------------------

    @Test
    public void testOnVarbitChanged_areaUnlock_callsMarkUnlocked() {
        monitor.initialize(); // seeds prev values to 0
        VarbitChanged event = buildVarbitEvent(VarbitID.LEAGUE_AREA_SELECTION_0, 9); // varlamore
        monitor.onVarbitChanged(event);
        verify(goalStore).markUnlocked("varlamore");
    }

    @Test
    public void testOnVarbitChanged_sameValue_doesNotCallMarkUnlocked() {
        // Seed slot 0 to value 1 (asgarnia) via initialize
        when(client.getVarbitValue(VarbitID.LEAGUE_AREA_SELECTION_0)).thenReturn(1);
        monitor.initialize();
        reset(goalStore); // clear calls made during initialize

        // Fire event with same value — no change, no re-trigger
        VarbitChanged event = buildVarbitEvent(VarbitID.LEAGUE_AREA_SELECTION_0, 1);
        monitor.onVarbitChanged(event);
        verify(goalStore, never()).markUnlocked(anyString());
    }

    @Test
    public void testOnVarbitChanged_unknownAreaIndex_doesNotCallMarkUnlocked() {
        monitor.initialize();
        // Index 99 has no mapping — should log warning but not throw or mark anything
        VarbitChanged event = buildVarbitEvent(VarbitID.LEAGUE_AREA_SELECTION_0, 99);
        monitor.onVarbitChanged(event);
        verify(goalStore, never()).markUnlocked(anyString());
    }

    @Test
    public void testOnVarbitChanged_zeroValue_doesNotCallMarkUnlocked() {
        monitor.initialize();
        // Value 0 = unselected — must not trigger markUnlocked
        VarbitChanged event = buildVarbitEvent(VarbitID.LEAGUE_AREA_SELECTION_2, 0);
        monitor.onVarbitChanged(event);
        verify(goalStore, never()).markUnlocked(anyString());
    }

    @Test
    public void testOnVarbitChanged_irrelevantVarbit_ignored() {
        monitor.initialize();
        // Some unrelated varbit — should not touch goalStore
        VarbitChanged event = buildVarbitEvent(99999, 1);
        monitor.onVarbitChanged(event);
        verify(goalStore, never()).markUnlocked(anyString());
    }

    @Test
    public void testOnVarbitChanged_relicVarbit_logsWithoutException() {
        monitor.initialize();
        // Relic varbits with values not in RELIC_INDEX_TO_ID should not throw
        VarbitChanged event = buildVarbitEvent(Varbits.LEAGUE_RELIC_1, 1);
        monitor.onVarbitChanged(event); // no exception expected
    }

    // ------------------------------------------------------------------
    // Area coverage: spot-check a few known area values
    // ------------------------------------------------------------------

    @Test
    public void testKnownAreaValues() {
        monitor.initialize();

        String[][] checks = {
            {"1",  "asgarnia"},
            {"3",  "fremennik"},
            {"5",  "karamja"},
            {"8",  "tirannwn"},
            {"10", "wilderness"},
        };

        for (String[] check : checks) {
            int val = Integer.parseInt(check[0]);
            String expectedId = check[1];
            VarbitChanged event = buildVarbitEvent(VarbitID.LEAGUE_AREA_SELECTION_0, val);
            // Reset prev to -1 so the change always fires
            monitor = new LeagueStatusMonitor(client, eventBus);
            monitor.setGoalStore(goalStore);
            monitor.initialize(); // prev=0 for all slots
            monitor.onVarbitChanged(event);
            verify(goalStore, atLeastOnce()).markUnlocked(expectedId);
            reset(goalStore);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static VarbitChanged buildVarbitEvent(int varbitId, int value) {
        VarbitChanged e = new VarbitChanged();
        e.setVarbitId(varbitId);
        e.setValue(value);
        return e;
    }
}
