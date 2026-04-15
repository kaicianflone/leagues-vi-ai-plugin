# TODOS

Deferred work from planning and review sessions. Ordered for single-pass execution: P1 → P2 → P3.

---

## Completed

### WikiSyncTaskLoader: client API called from background thread ✓

**Fix:** Captured player name and profile type on the calling (game) thread before `executor.submit()`. Removed the 10-attempt retry loop — it was masking the threading violation. A null player at call time now logs a warning and returns; the LOGGED_IN event fires again on world hop.

**File:** `src/main/java/com/leaguesai/core/monitors/WikiSyncTaskLoader.java`

---

### completedTasks never reset on account / login switch ✓

**Fix:** Added `PlayerContextAssembler.reset()` which clears `completedTasks` and `unlockedAreas`. Called from `LeaguesAiPlugin.onGameStateChanged` before LOGGED_IN triggers `leagueStatusMonitor.initialize()` and `wikiSyncTaskLoader.loadCompletedTasks()`.

**Files:** `src/main/java/com/leaguesai/agent/PlayerContextAssembler.java`, `src/main/java/com/leaguesai/LeaguesAiPlugin.java`

---

### League points varbit unknown ✓

**Fix:** `VarPlayerID.LEAGUE_POINTS_CURRENCY` confirmed present in RuneLite API jar 1.12.24 (VarPlayer 2613, spendable balance). Wired into `PlayerContextAssembler.assembleOnClientThread()` via `client.getVarpValue(VarPlayerID.LEAGUE_POINTS_CURRENCY)`. Also logged in `LeagueStatusMonitor.initialize()`.

**Files:** `src/main/java/com/leaguesai/agent/PlayerContextAssembler.java`, `src/main/java/com/leaguesai/core/monitors/LeagueStatusMonitor.java`

---

### Relic index mapping design flaw ✓

**Fix:** Changed `RELIC_INDEX_TO_ID: Map<Integer, String>` to `RELIC_SLOT_MAP: Map<Integer, Map<Integer, String>>` keyed by `(slotIndex, value)`. `handleRelicValue` now calls `RELIC_SLOT_MAP.getOrDefault(slot, emptyMap()).get(value)`. Map is intentionally empty until live relic varbit values are confirmed from in-game data — log output on first login will capture the (slot, value) pairs needed to fill it.

**File:** `src/main/java/com/leaguesai/core/monitors/LeagueStatusMonitor.java`

---

### Refactor PromptBuilder into mode-specific context builders ✓

**Fix:** Extracted `ModeContextBuilder` interface + `LeaguesContextBuilder` and `IronmanContextBuilder` implementations. `PromptBuilder.buildSystemPromptImpl()` selects the builder via `ctx.isLeaguesMode()` and delegates the three mode-specific sections (`buildIntro()`, `buildExtraSection()`, `buildPlayerStateExtras(ctx)`) to it. Removed all `if (leaguesMode)` blocks from `buildSystemPromptImpl`. No behavior change.

**Files:** `src/main/java/com/leaguesai/agent/ModeContextBuilder.java` (new), `src/main/java/com/leaguesai/agent/LeaguesContextBuilder.java` (new), `src/main/java/com/leaguesai/agent/IronmanContextBuilder.java` (new), `src/main/java/com/leaguesai/agent/PromptBuilder.java`
