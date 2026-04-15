# TODOS

Deferred work from planning and review sessions. Ordered for single-pass execution: P1 → P2 → P3.

---

## P1 — Fix now

### WikiSyncTaskLoader: client API called from background thread

**What:** `client.getLocalPlayer()` and `player.getName()` are read inside `executor.submit()` (lines 99-100 of `WikiSyncTaskLoader.java`). RuneLite requires all client API calls on the game thread. Background reads can return stale data, produce null on world hop, or crash under load.

**Fix:** In `loadCompletedTasks()`, capture the player name on the calling thread before `executor.submit()`:
```java
Player player = client.getLocalPlayer();
String playerName = player != null ? player.getName() : null;
executor.submit(() -> {
    if (playerName == null) { /* retry or bail */ return; }
    fetchAndMark(playerName, profileFinal);
});
```
Remove the 10-attempt retry loop (it was working around the threading issue). A null player at call time means the caller should call later (LOGGED_IN fires again on world hop).

**File:** `src/main/java/com/leaguesai/core/monitors/WikiSyncTaskLoader.java:87-109`

**Effort:** XS (< 15 min)
**Priority:** P1 — threading violation, can crash client

---

### completedTasks never reset on account / login switch

**What:** `PlayerContextAssembler.completedTasks` and `unlockedAreas` are `HashSet`s that are only ever added to, never cleared. If the player logs out and back in (or switches accounts), the previous session's completed tasks stay marked, permanently suppressing them from the planner.

**Fix:** Add a `reset()` method to `PlayerContextAssembler`:
```java
public synchronized void reset() {
    completedTasks.clear();
    unlockedAreas.clear();
}
```
Call it from `LeaguesAiPlugin.onGameStateChanged` before LOGGED_IN triggers `loadCompletedTasks()` and `initialize()`. This ensures each login session starts clean.

**File:** `src/main/java/com/leaguesai/agent/PlayerContextAssembler.java`, `src/main/java/com/leaguesai/LeaguesAiPlugin.java`

**Effort:** XS (< 15 min)
**Priority:** P1 — silently corrupts planner output after logout/relog

---

## P2 — Fix soon

### League points varbit unknown

**What:** `PlayerContextAssembler.java:91` hardcodes `leaguePoints(0)` because the Demonic Pacts League points varbit ID is not yet in the RuneLite `VarbitID` enum and hasn't been confirmed from live game data. `LEAGUE_COMBAT_MASTERY_POINTS_TO_SPEND` / `_EARNED` exist but are a different system.

**How to find it:** On launch day in-game, use the RuneLite Dev Tools (Varbit plugin) and filter on `LEAGUE` while earning a task completion — the points counter varbit will light up. Alternatively, monitor `#osrs-leagues` Discord / OSRS Wiki for community-identified varbit IDs.

**Fix once known:** Add to `LeagueStatusMonitor.initialize()`:
```java
int pts = client.getVarbitValue(VarbitID.LEAGUE_POINTS); // replace with real ID
log.info("LeagueStatusMonitor: league points = {}", pts);
```
Wire into `PlayerContextAssembler` via a new `setLeaguePoints(int)` or read directly in `assembleOnClientThread()`.

**File:** `src/main/java/com/leaguesai/agent/PlayerContextAssembler.java:91`, `src/main/java/com/leaguesai/core/monitors/LeagueStatusMonitor.java`

**Effort:** XS once varbit ID known; blocked on community research
**Priority:** P2 — planner has no points context; area unlock cost validation blocked on this

---

## P3 — Post-launch backlog

### Relic index mapping design flaw

**What:** `LeagueStatusMonitor.RELIC_INDEX_TO_ID` is a flat `Map<Integer, String>`. But relic varbit values are tier-local: each of the 8 tier slots stores 0/1/2/3 meaning "no relic / first option / second option / third option". Slot 1 value 1 and Slot 2 value 1 are different relics. A flat global map keyed only on value 1 cannot distinguish them — it maps both to the same relic id.

**Fix:** Change to `Map<Integer, Map<Integer, String>>` keyed by `(slotIndex, value)`:
```java
private static final Map<Integer, Map<Integer, String>> RELIC_SLOT_MAP = new HashMap<>();
static {
    Map<Integer, String> tier1 = new HashMap<>();
    tier1.put(1, "relic_tier1_option1"); // fill from live game data
    RELIC_SLOT_MAP.put(0, tier1);
    // ... per slot
}
```
Then in `handleRelicValue(int slot, int value)`: `RELIC_SLOT_MAP.getOrDefault(slot, emptyMap()).get(value)`.

**Currently inert:** `RELIC_INDEX_TO_ID` is empty, so no wrong relics are mapped today. Fix after live varbit indices are confirmed.

**File:** `src/main/java/com/leaguesai/core/monitors/LeagueStatusMonitor.java:104`

**Effort:** S
**Priority:** P3 — blocks relic auto-unlock detection working correctly; currently inert

---

### Refactor PromptBuilder into mode-specific context builders

**What:** Extract `LeaguesContextBuilder` and `IronmanContextBuilder` classes. `PromptBuilder` delegates to the correct one based on `ctx.isLeaguesMode()`. Eliminates the `if (leaguesMode)` blocks scattered through `PromptBuilder.buildSystemPromptImpl()`.

**Why:** Approach A (config flag + `if` blocks) was chosen for the pre-launch window. This is the post-launch cleanup to Approach B (independent per-mode builders). The 600-line `PromptBuilder` utility has too many responsibilities and the inline conditionals make it hard to extend to a third mode (e.g., GIM).

**Context:** `// TODO(post-launch): refactor into LeaguesContextBuilder + IronmanContextBuilder` marks the location in `PromptBuilder.java:87`. No behavior change — pure refactor. Approach B architecture was designed in the 2026-04-12 CEO plan review.

**Effort:** M human → S with CC
**Priority:** P3
**Depends on:** Ironman+Leagues toggle (Approach A, PR 5) already shipped ✓

---

## Completed

_(nothing yet — items move here as they ship)_
