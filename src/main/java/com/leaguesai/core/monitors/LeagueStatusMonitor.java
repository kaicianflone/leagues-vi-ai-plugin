package com.leaguesai.core.monitors;

import com.leaguesai.data.GoalStore;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * Watches league-specific varbits for relic / area unlock events and calls
 * {@link GoalStore#markUnlocked(String)} so the UI reflects the player's
 * actual in-game state without requiring manual confirmation.
 *
 * <p><b>Area varbits:</b> {@code LEAGUE_AREA_SELECTION_0..5} (IDs 10662-10667).
 * Each stores the area index the player chose for that unlock slot.
 * Index 0 = no area selected. The mapping from index → area id below must be
 * verified in-game on launch day — log lines flag any unknown index so we can
 * update quickly.
 *
 * <p><b>Relic varbits:</b> {@code LEAGUE_RELIC_1..8} (Varbits enum, IDs 10049-10053,
 * 11696, 17301, 17302). Each stores the relic index chosen for that tier slot.
 * Index 0 = no relic selected. Same caveat on index mapping.
 *
 * <p>On startup, {@link #initialize()} reads all slots so already-unlocked state
 * is picked up immediately (not just on next varbit change).
 */
@Singleton
public class LeagueStatusMonitor {

    private static final Logger log = LoggerFactory.getLogger(LeagueStatusMonitor.class);

    // -----------------------------------------------------------------------
    // Area varbit IDs (LEAGUE_AREA_SELECTION_0..5)
    // -----------------------------------------------------------------------
    private static final int[] AREA_VARBIT_IDS = {
        VarbitID.LEAGUE_AREA_SELECTION_0,
        VarbitID.LEAGUE_AREA_SELECTION_1,
        VarbitID.LEAGUE_AREA_SELECTION_2,
        VarbitID.LEAGUE_AREA_SELECTION_3,
        VarbitID.LEAGUE_AREA_SELECTION_4,
        VarbitID.LEAGUE_AREA_SELECTION_5,
    };

    /**
     * Maps the varbit value (area index) → area id as stored in the DB.
     * Value 0 means "no selection". Unknown values are logged as warnings so
     * the map can be corrected from day-1 logs.
     *
     * Populated from wiki data; verify against live game on launch day.
     * The wiki area slugs match the DB area column set by the scraper.
     */
    private static final Map<Integer, String> AREA_INDEX_TO_ID = new HashMap<>();
    static {
        // Demonic Pacts League area indices — verify on launch day.
        // Index 0 = unselected (skip). Indices 1-11 map to the 11 selectable areas.
        AREA_INDEX_TO_ID.put(1,  "asgarnia");
        AREA_INDEX_TO_ID.put(2,  "desert");
        AREA_INDEX_TO_ID.put(3,  "fremennik");
        AREA_INDEX_TO_ID.put(4,  "kandarin");
        AREA_INDEX_TO_ID.put(5,  "karamja");
        AREA_INDEX_TO_ID.put(6,  "kourend");
        AREA_INDEX_TO_ID.put(7,  "morytania");
        AREA_INDEX_TO_ID.put(8,  "tirannwn");
        AREA_INDEX_TO_ID.put(9,  "varlamore");
        AREA_INDEX_TO_ID.put(10, "wilderness");
        // Index 11 would be the 11th area if one existed; Varlamore is free so
        // there are 10 selectable regions. Add more entries if wiki data differs.
    }

    // -----------------------------------------------------------------------
    // Relic varbit IDs (Varbits.LEAGUE_RELIC_1..8)
    // -----------------------------------------------------------------------
    private static final int[] RELIC_VARBIT_IDS = {
        Varbits.LEAGUE_RELIC_1,   // 10049
        Varbits.LEAGUE_RELIC_2,   // 10050
        Varbits.LEAGUE_RELIC_3,   // 10051
        Varbits.LEAGUE_RELIC_4,   // 10052
        Varbits.LEAGUE_RELIC_5,   // 10053
        Varbits.LEAGUE_RELIC_6,   // 11696
        Varbits.LEAGUE_RELIC_7,   // 17301
        Varbits.LEAGUE_RELIC_8,   // 17302
    };

    /**
     * Maps varbit value (relic index) → relic id as stored in the DB.
     * Value 0 = no relic chosen for this tier. Unknown values are logged.
     *
     * Demonic Pacts League has 3 relics per tier (8 tiers × 3 options).
     * Indices within a tier: 1, 2, 3. The relic id is the lowercase name slug
     * from the scraper (e.g. "dragon_slayer").
     *
     * This map MUST be verified against live game data on launch day.
     * The structure below is a placeholder — populate from the DB once live.
     */
    private static final Map<Integer, String> RELIC_INDEX_TO_ID = new HashMap<>();
    // Intentionally empty for launch day — relic varbit index values are not yet confirmed.
    // Populate once live game data is verified: RELIC_INDEX_TO_ID.put(index, "relic_id").

    private final Client client;
    private final EventBus eventBus;

    /**
     * GoalStore is constructed manually by the plugin (not Guice-managed), so
     * it is injected via setter rather than the constructor. The monitor is safe
     * to register before setGoalStore() is called — handleAreaValue /
     * handleRelicValue are no-ops when goalStore is null.
     */
    private volatile GoalStore goalStore;

    // Track previous values to fire events only on change
    private final int[] prevAreaValues;
    private final int[] prevRelicValues;

    @Inject
    public LeagueStatusMonitor(Client client, EventBus eventBus) {
        this.client = client;
        this.eventBus = eventBus;
        prevAreaValues  = new int[AREA_VARBIT_IDS.length];
        prevRelicValues = new int[RELIC_VARBIT_IDS.length];
    }

    public void setGoalStore(GoalStore goalStore) {
        this.goalStore = goalStore;
    }

    /**
     * Read all area and relic varbits once at startup so already-unlocked
     * state is reflected immediately. Must be called after the client is logged
     * in (i.e. from startUp / GameState.LOGGED_IN).
     */
    public void initialize() {
        log.info("LeagueStatusMonitor: reading startup varbit state");

        for (int i = 0; i < AREA_VARBIT_IDS.length; i++) {
            int val = client.getVarbitValue(AREA_VARBIT_IDS[i]);
            prevAreaValues[i] = val;
            log.info("LeagueStatusMonitor: AREA slot {} varbit={} value={}", i, AREA_VARBIT_IDS[i], val);
            handleAreaValue(i, val);
        }

        for (int i = 0; i < RELIC_VARBIT_IDS.length; i++) {
            int val = client.getVarbitValue(RELIC_VARBIT_IDS[i]);
            prevRelicValues[i] = val;
            log.info("LeagueStatusMonitor: RELIC slot {} varbit={} value={}", i, RELIC_VARBIT_IDS[i], val);
            handleRelicValue(i, val);
        }

        int totalTasks = client.getVarbitValue(VarbitID.LEAGUE_TOTAL_TASKS_COMPLETED);
        log.info("LeagueStatusMonitor: LEAGUE_TOTAL_TASKS_COMPLETED = {}", totalTasks);
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        int varbitId = event.getVarbitId();
        int value    = event.getValue();

        // Check area varbits
        for (int i = 0; i < AREA_VARBIT_IDS.length; i++) {
            if (AREA_VARBIT_IDS[i] == varbitId && value != prevAreaValues[i]) {
                log.info("LeagueStatusMonitor: AREA slot {} changed {} -> {}", i, prevAreaValues[i], value);
                prevAreaValues[i] = value;
                handleAreaValue(i, value);
                return;
            }
        }

        // Check relic varbits
        for (int i = 0; i < RELIC_VARBIT_IDS.length; i++) {
            if (RELIC_VARBIT_IDS[i] == varbitId && value != prevRelicValues[i]) {
                log.info("LeagueStatusMonitor: RELIC slot {} changed {} -> {}", i, prevRelicValues[i], value);
                prevRelicValues[i] = value;
                handleRelicValue(i, value);
                return;
            }
        }
    }

    private void handleAreaValue(int slot, int value) {
        if (value == 0) return; // 0 = unselected
        String areaId = AREA_INDEX_TO_ID.get(value);
        if (areaId != null) {
            log.info("LeagueStatusMonitor: area unlocked — slot={} value={} id={}", slot, value, areaId);
            if (goalStore != null) goalStore.markUnlocked(areaId);
        } else {
            log.warn("LeagueStatusMonitor: UNKNOWN area value {} in slot {} — update AREA_INDEX_TO_ID", value, slot);
        }
    }

    private void handleRelicValue(int slot, int value) {
        if (value == 0) return; // 0 = no relic chosen
        String relicId = RELIC_INDEX_TO_ID.get(value);
        if (relicId != null) {
            log.info("LeagueStatusMonitor: relic unlocked — slot={} value={} id={}", slot, value, relicId);
            if (goalStore != null) goalStore.markUnlocked(relicId);
        } else {
            // Not an error — RELIC_INDEX_TO_ID is intentionally sparse until
            // populated from live game data. Log at INFO level for day-1 mapping.
            log.info("LeagueStatusMonitor: relic value {} in slot {} (no id mapping yet)", value, slot);
        }
    }
}
