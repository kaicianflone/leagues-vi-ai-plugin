# Phase 2 — Launch Day Wiring Plan
<!-- /autoplan restore point: /Users/kaicianflone/.gstack/projects/kaicianflone-leagues-vi-ai-plugin/main-autoplan-restore-20260415-080753.md -->

**Branch:** main  
**Date:** 2026-04-15 (Leagues VI launch day)  
**Status:** Tasks scraped — 1,422 rows. Phase 2 wiring begins.

---

## Context

The Demonic Pacts League wiki went live today. Scraper ran: 1,422 tasks, 23 relics,
10 areas, 78 pacts, 0 errors. Several data gaps remain that we can now fix:

- `area` column: ALL rows show "general" — the scraper uses the old Trailblazer attribute
  `data-tbz-area-for-filtering` but the live wiki uses `data-league-area-for-filtering`.
  Real areas: asgarnia, desert, fremennik, general, kandarin, karamja, kourend, morytania,
  tirannwn, varlamore, wilderness.
- `category`: All empty — `data-pact-task="yes"` on 75 rows is not captured.
- `difficulty`: Working via image, but `data-league-tier` is available directly on each row.
- `skills_required`: Working correctly for tasks with skill requirements.
- `items_required`: Empty — requirements like "Any axe" are not parsed to item format.
- `unlock_cost` (areas): 0 — wiki does not list costs yet. SKIP.
- `parent_id` (pacts): null — wiki has no tree yet. SKIP.

---

## Goals

1. Fix scraper data gaps (attribute names, pact flag, item requirements)
2. Re-run scraper so DB has correct area / category data
3. Add task filter UI in GoalsPanel (area + difficulty + pact-task chips)
4. Wire in-game unlock detection via LeagueStatusMonitor + varbits

---

## Implementation Steps

### Step 1 — Fix HtmlParser.parseTaskTableRich (scraper)

**File:** `scraper/src/main/java/com/leaguesai/scraper/HtmlParser.java`

Changes:
- Line 97: Change `row.attr("data-tbz-area-for-filtering")` →
  `row.attr("data-league-area-for-filtering")`
- Add `tr.isPactTask = "yes".equalsIgnoreCase(row.attr("data-pact-task"))` to TaskRow parsing
- Add `tr.difficulty` fallback: try `row.attr("data-league-tier")` first, then image-based
  logic as secondary. This makes difficulty scraping robust against future image renames.
- Add `public boolean isPactTask` field to `TaskRow`

**Impact:** Fixes area distribution across 11 regions. Captures 75 pact tasks.

### Step 2 — Store pact-task flag as category in WikiScraper + SqliteWriter

**File:** `scraper/src/main/java/com/leaguesai/scraper/WikiScraper.java`

Changes:
- Pass `row.isPactTask ? "pact" : null` as the `category` argument to `upsertTaskWithId`
  (currently always passing `null`)

**SqliteWriter:** `upsertTaskWithId` already accepts `category` — no changes needed there.

### Step 3 — Parse item requirements

**File:** `scraper/src/main/java/com/leaguesai/scraper/TaskNormalizer.java`

Add `parseItemRequirements(String reqText)`:
- Pattern: text in requirements that does NOT match `\d+ SkillName` (i.e. not a skill req)
- Examples: "Any axe", "Tinderbox", "Bow and arrow"
- Return a JSON array of requirement strings: `["Any axe"]`
- Keep it simple — store as a flat string list, not item IDs (IDs not known yet)

**WikiScraper.java:** Pass result to `items_required` argument (currently `null`).

### Step 4 — Re-run scraper

After Step 1-3:
```
./scraper/scrape.sh
```

Verify:
```sql
SELECT area, COUNT(*) FROM tasks GROUP BY area;
SELECT COUNT(*) FROM tasks WHERE category = 'pact';
SELECT COUNT(*) FROM tasks WHERE items_required IS NOT NULL AND items_required != '[]';
```

Expected: 11 distinct areas, ~75 pact tasks, some items_required populated.

### Step 5 — Task browser + filter UI in GoalsPanel

**DECISION (user-confirmed):** Build task browser + filter bar. No task list exists in
GoalsPanel today — this is a new browsable task section, not just a filter bar.

**Files:**
- `src/main/java/com/leaguesai/ui/GoalsPanel.java` — add task browser section
- `src/main/java/com/leaguesai/data/TaskRepository.java` — add `findFiltered` method
- `src/main/java/com/leaguesai/data/TaskRepositoryImpl.java` — implement `findFiltered`

**Task browser section (below active plan accordion):**
- Renders a scrollable list of task cards, limited to 50 rows at a time (lazy-load or
  simple "Show more" button to avoid Swing performance issues with 1,422 rows)
- Each task card: name, difficulty badge, area badge, points value
- Collapsed by default (expand with "Browse Tasks" toggle)

**Filter bar (inside the task browser section header):**
- **Area**: `JComboBox` with "All Areas" + 11 region names (title-case: "Asgarnia",
  "Desert", "Fremennik", "General", "Kandarin", "Karamja", "Kourend", "Morytania",
  "Tirannwn", "Varlamore", "Wilderness")
- **Difficulty**: 4 small toggle buttons Easy / Medium / Hard / Elite (all active by
  default, RuneLite-styled with the plugin's existing color palette)
- **Pact tasks only**: `JCheckBox`

Wire to `TaskRepository.findFiltered(area, difficulties, pactOnly)`.

Filter state is session-only (no persistence needed).

### Step 6 — LeagueStatusMonitor (in-game unlock detection)

**New file:** `src/main/java/com/leaguesai/core/monitors/LeagueStatusMonitor.java`

This is the live game state bridge. It subscribes to `VarbitChanged` events from the
RuneLite event bus and calls `GoalStore.markUnlocked(id)` when a relic/area/pact unlock
fires.

**Varbit research needed:** Leagues VI varbits are not in RuneLite's `Varbits` enum yet
(or may be in `VarPlayer` config). Approach:
1. Use `client.getVarbitValue(VARBIT_ID)` once known
2. Alternatively, watch for the `WidgetLoaded` / `ChatMessage` events that fire on unlock
   (the "You have unlocked the X area!" message is reliable)

**Launch day implementation (user-confirmed: ship with best-guess + verbose logging):**
- Subscribe to `ChatMessage` events
- Pattern match on messages containing "unlocked" (case-insensitive broad match)
- Log EVERY message that hits the pattern: `log.info("LeagueStatus: unlock candidate: {}", msg)`
- If pattern matches a known area name → `goalStore.markUnlocked(normalizedId)`
- If pattern matches but no known area/relic → log WARNING: unknown unlock message
- This allows day-1 wording verification from logs without missing unlocks

**File:** `src/main/java/com/leaguesai/LeaguesAiPlugin.java`
- Subscribe `LeagueStatusMonitor` to the event bus in `startUp()`

### Step 7 — Tests

**Scraper tests:**
- `HtmlParserTest.java`: add test for `data-league-area-for-filtering` parsing
- `HtmlParserTest.java`: add test for `data-pact-task` → `isPactTask = true`
- `TaskNormalizerTest.java`: add test for `parseItemRequirements`

**Monitor tests:**
- `LeagueStatusMonitorTest.java`: mock `ChatMessage` events for area/relic unlocks,
  assert `GoalStore.markUnlocked` called with correct id

---

## What We Are NOT Doing (confirmed wiki gaps)

- Area unlock costs: `areas.unlock_cost` stays 0. Wiki has no cost data yet.
  `UnlockablesPanel` already renders "cost TBD" for cost=0.
- Pact unlock tree: `pacts.parent_id` stays null. No tree structure on wiki.
  `UnlockablesPanel` flat list is correct behavior.
- GearRepository real item IDs: depends on task IDs being stable — wait for
  post-launch confirmation.
- Auto-completed quests: low priority, no consumer in plugin yet.

---

## Files Modified

| File | Change |
|------|--------|
| `scraper/src/main/java/.../HtmlParser.java` | Fix area attr, add pact flag, tier fallback |
| `scraper/src/main/java/.../WikiScraper.java` | Pass category + items_required |
| `scraper/src/main/java/.../TaskNormalizer.java` | Add parseItemRequirements |
| `src/main/java/.../ui/GoalsPanel.java` | Task browser section + filter bar (area, difficulty, pact-only) |
| `src/main/java/.../data/TaskRepository.java` | Add findFiltered(area, difficulties, pactOnly) |
| `src/main/java/.../data/TaskRepositoryImpl.java` | Implement findFiltered |
| `src/main/java/.../core/monitors/LeagueStatusMonitor.java` | New — ChatMessage unlock detection |
| `src/main/java/.../LeaguesAiPlugin.java` | Subscribe LeagueStatusMonitor |
| `src/test/java/.../scraper/HtmlParserTest.java` | New area + pact tests |
| `src/test/java/.../scraper/TaskNormalizerTest.java` | parseItemRequirements test |
| `src/test/java/.../core/monitors/LeagueStatusMonitorTest.java` | New |

---

## Open Questions (USER CHALLENGE — needs your input)

### Challenge 1: Task browser vs filter bar (both CEO + Eng flagged)

**You said:** "Add task filter UI in GoalsPanel (area, difficulty, pact-only chips)"

**What the code shows:** GoalsPanel has no task list — it only shows `GoalAccordion`
(active plan steps). A filter bar with nothing to filter is empty UI.

**Both reviews flag:** To filter tasks, a task browser must exist first. That's a new
JScrollPane + paginated task list rendering 1,422 rows, then the filter bar on top.
Realistic effort: M (human ~2 days / CC ~45 min).

**Alternative:** Defer the filter bar entirely and ship only scraper fixes + unlock detection
on launch day. Task browser in a follow-up PR when the heat dies down.

**If we're wrong about deferring:** Players will have correct area data in the DB but no
way to browse tasks by area in the panel. The AI planner already routes by area correctly
(it reads `task.area` from the DB). So the planner still works. Players just can't manually
browse.

**Your call — original direction (build filter) stands unless you change it.**

### Challenge 2: ChatMessage wording for unlock detection

**What we need:** Exact text of "You have unlocked..." messages for areas and relics
in Leagues VI. Wrong wording = zero unlocks detected silently.

**Options:**
- A) Ship with best-guess pattern + add detailed log line so you catch mismatches on day 1
- B) Wait until you can verify in-game before implementing LeagueStatusMonitor
- C) Implement + add a manual "mark unlocked" button in the UI as a fallback

---

## Decision Audit Trail

| # | Phase | Decision | Classification | Principle | Rationale | Rejected |
|---|-------|----------|----------------|-----------|-----------|---------|
| 1 | CEO | Approach A (fix + wire all) | Mechanical | P1 Completeness | All problems in blast radius, near-zero cost | B (defers UI), C (blocked on varbit IDs) |
| 2 | CEO | Varbit upgrade deferred | Mechanical | P6 Bias toward action | Ship ChatMessage approach today; upgrade when IDs known | Approach C |
| 3 | CEO | Filter persistence deferred | Mechanical | P3 Pragmatic | Session-only filter fine for launch day | Persistent filter |
| 4 | Eng | Build task browser + filter bar | USER CHALLENGE → ACCEPTED | P1 Completeness | User confirmed: build browser + filters | Defer filter bar |
| 5 | Eng | Best-guess ChatMessage + verbose log | USER CHALLENGE → ACCEPTED | P6 Bias toward action | Ship with log-based day-1 verification | Wait for in-game verify |
