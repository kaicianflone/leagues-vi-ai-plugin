# Changelog

All notable changes to the Leagues VI AI Plugin. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning is informal
pre-launch (2026-04-15); the first tagged release will be cut on or after
launch day.

## [0.5.0.0] — 2026-04-15 — Phase 2 Launch Day: Task Browser, Unlock Detection, WikiSync

Browse all 1,422 Demonic Pacts League tasks directly in the panel — filter by area,
difficulty, and pact-only. The plugin now detects area unlocks in real time via game
varbits and marks them automatically. WikiSync integration pulls your completed tasks
from the wiki server on login, so the planner excludes tasks you've already done. Step
overlays now show a persistent HUD with the current task name, difficulty, area badge,
and wiki link. Proximity sorting for multi-task plans runs twice as fast.

### Added

- **Task browser** — new collapsible "Browse Tasks" section in GoalsPanel. Shows all
  tasks in the DB with area/difficulty/pact-only filters and 50-task paged loading.
  Backed by `TaskRepository.findFiltered()` with stable point-descending sort.
- **`StepInstructionOverlay`** — always-on HUD overlay (top-left) showing the current
  step name, step counter (N / M), difficulty + area badge, and wiki URL shortcut when
  the step has no spatial targets.
- **`LeagueStatusMonitor`** — watches `LEAGUE_AREA_SELECTION_0..5` varbits and calls
  `GoalStore.markUnlocked()` when an area unlock fires in-game. Reads all slots on
  startup so pre-existing unlocks are picked up immediately.
- **`WikiSyncTaskLoader`** — background service that fetches completed task IDs from
  `sync.runescape.wiki` on login. Matches against the local DB so the planner
  automatically excludes tasks you've already completed.
- **`AreaHubs`** — representative WorldPoints for each Leagues VI region, used as
  fallback minimap targets when a task has no scraped precise location.
- **`TaskRepository.findFiltered()`** — new interface method + implementation with
  area (case-insensitive), difficulty set, pact-only, and offset/limit pagination.

### Changed

- **Scraper: area attribute** — `HtmlParser` now reads `data-league-area-for-filtering`
  (live wiki attribute) instead of the old `data-tbz-area-for-filtering`, fixing area
  distribution across all 11 regions.
- **Scraper: pact tasks** — `HtmlParser` reads `data-pact-task` attribute and marks
  the 75 pact tasks with `category = "pact"`.
- **Scraper: item requirements** — `TaskNormalizer.parseItemRequirements()` extracts
  item requirement strings (e.g. "Any axe") from the requirements column.
- **Scraper: difficulty** — `HtmlParser` falls back to `data-league-tier` attribute
  before image-based difficulty detection, making scraping more robust.
- **`PlannerOptimizer`** — nearest-neighbor walk now uses squared distance (no
  `Math.sqrt`) and pre-computes hub WorldPoints once per sort instead of per-comparison.
  Net ~2x speedup for 50-task plans.
- **`PlayerContextAssembler`** — `getCompletedTasksSnapshot()` and
  `getUnlockedAreasSnapshot()` are now synchronized to prevent
  `ConcurrentModificationException` when WikiSyncTaskLoader writes concurrently.
- **`ChatService.maybeMarkCompleted`** — returns `false` when no task name matched,
  allowing the LLM to respond instead of swallowing the message.
- **Session restore + build activate** — `OverlayController.setPlanSize()` is now
  called before `setActiveStep()` in both paths so the HUD shows the correct Step N/M.

### Fixed

- `WikiSyncTaskLoader` username URL-encodes spaces in RSNs (e.g. "Iron Man") to
  prevent malformed HTTP requests and silent failures.
- `TaskBrowserPanel` initial difficulty set excludes MASTER (no UI button) so MASTER
  tasks are not silently hidden after the first filter interaction.
- `TaskRepositoryImpl.findFiltered` sorts results before pagination for stable page
  order across "Load more" clicks.
- `DatabaseLoader` logs malformed task rows to stderr instead of swallowing them silently.
- `WikiSyncTaskLoader` shuts down its background executor on plugin teardown.

## [0.4.0.0] — 2026-04-13 — PR 5: CRAFT Goals, Leagues Mode, Inventory Auto-Advance

You can now say "make a staff of air" or "smith rune platebody" and the plugin looks
up the recipe on the OSRS wiki, builds a material-gather plan, and walks you to each
shop or bank step with overlays. Ironman mode is now a toggleable setting that hides
leagues-only personas and disables relic/area/pact goals. Plan steps auto-advance when
you pick up the required item.

### Added

- **`GoalType.CRAFT`** — new goal type triggered by crafting verbs (make, craft, smith,
  fletch, brew, cook). `GoalSpecParser` detects intent and `GoalPlanner` calls
  `WikiItemLookup.lookupCraftingRecipe` to parse the recipe from OSRS wiki wikitext.
- **`WikiItemLookup`** — new class (`WikiItemLookup.java`). Fetches and parses OSRS wiki
  wikitext for `{{Crafting|`, `{{Smithing|`, `{{Fletching|`, `{{Cooking|`, `{{Herblore|`
  templates. Extracts materials, quantities, skill level, and XP. Also handles shop
  location lookups and `CRAFT_ALIASES` for common player aliases (e.g., "air staff").
- **`SkillingStepBuilder`** — new helper that assembles CRAFT plan steps (gather
  materials → craft) from a `CraftingRecipe`.
- **`ItemDependencyGraph.findLongestMatchingItem`** — two-pass item name matching:
  bag-of-words (order-agnostic, handles "air staff" → "Staff of air") then exact
  substring fallback. Pre-sorted longest-first list for O(k) lookup.
- **Inventory auto-advance** — `LeaguesAiPlugin.onInventoryStateEvent` watches the
  player's inventory and advances the active plan step when `completionItemIds` are
  satisfied. `PlannedStep.completionItemMinQtys` added so bank steps require
  `coins >= shopValue`, not just `coins > 0`.
- **Leagues/ironman mode toggle** — `LeaguesAiConfig.leaguesMode` boolean config key.
  `SettingsPanel` now has a checkbox (persisted across restarts). Leagues-only personas
  (points_chaser, build_architect, explorer, sprinter, sirpugger) are hidden in ironman
  mode. `GoalSpecParser` gates RELIC/AREA/PACT goal shapes on the mode flag.
- **`scrape-meta.sh`** — new scraper script for item stats via wiki Infobox_Bonuses
  transclusion query.
- **CRAFT test suite** — `WikiItemLookupTest`, `GoalSpecParserCraftTest`,
  `GoalPlannerCraftTest`, `ItemMatchingTest`, `CraftableItemsCatalog` (19 items across
  Crafting/Smithing/Fletching/Cooking/Herblore). 406 tests total, 0 failures.

### Changed

- **`ItemStatsScraper`** rewritten from Cargo API (unavailable on OSRS wiki) to
  `Template:Infobox_Bonuses` transclusion query + batch wikitext parse.
- **`WikiScraper`** extended to scrape item shop data alongside tasks.
- **`PromptBuilder`** updated to include `leaguesMode` in player context.
- **`UnlockablesPanel`** overhauled: collapsible sections, search filter, improved
  pact tree rendering.
- **`ChatPanel`** plan-just-created flag prevents "you already have a plan" reply
  immediately after a plan is built.
- **`WidgetOverlay`** ported additional Quest Helper widget highlight patterns.

### Fixed

- `SettingsPanel` leagues-mode checkbox was hardcoded to `true` on startup,
  ignoring saved `leaguesMode=false` config. Now calls `setLeaguesMode(initialValue)`
  at startup.
- Bank step auto-advance fired on any coins present (`coins > 0`) instead of enough
  coins (`coins >= shopValue`). Fixed via `PlannedStep.completionItemMinQtys`.
- `activePlanSteps`/`activePlanStepIndex` volatile write order inverted — game thread
  could see new step list with stale index from previous plan. Fixed by writing index=0
  before setting the new step list.
- `WikiItemLookup` `OkHttpClient` was never closed on plugin reload, leaking dispatcher
  threads. Now implements `Closeable`; `shutDown()` calls `wikiItemLookup.close()`.
- Template skill detection in `lookupCraftingRecipe` picked array-first template instead
  of wikitext-first. Fixed to use `indexOf` position.
- `ItemDependencyGraph` catalog slug inserts now use `putIfAbsent` to preserve
  `item_dependencies` data priority over catalog stubs.

## [0.3.0.0] — 2026-04-10 — PR 3: Builds System

Friends can now load a curated build (Melee Bosser, Ranged PvM, Skiller, etc.),
and the planner chains every relic/area/pact/gear-task prerequisite automatically.
Builds are portable JSON files you can pass around in Discord.

### Added

- **Builds system** — 5 seeded build archetypes (`src/main/resources/builds.json`):
  Melee Bosser, Ranged PvM DPS, Skiller/Point Farmer, Ironmeme One-Defence Pure,
  All-Rounder Starter. Each declares target gear per slot, required relics/areas/pacts,
  and optional skill targets.
- **`BuildsPanel`** — new card UI accessible via "Browse Builds" in the Goals tab.
  Sub-tabs: Templates / Saved. Each card shows pact budget (X/40 with warning if over),
  relic/area counts, and Activate + Export buttons. Import accepts Discord-shared JSON.
- **`BuildStore`** — atomic JSON persistence (`~/.runelite/leagues-ai/data/builds.json`).
  Import validates schema, id allowlist, and max-5-per-file cap. Seeds load from classpath.
- **`BuildExpander`** — resolves a build into a `CompositeGoal`. For each gear slot, looks
  up granting tasks via `TaskRepository.findByTargetItemId` (in-memory int comparison,
  no LIKE collision). Falls back to `gear.json` `taskOverrides` for launch-day reliability.
  Returns goals-only mode gracefully when `target_items` column is empty.
- **`GoalType.BUILD`** + `GoalSpec.terminalTaskIds` — new goal type that bypasses the
  relic/area gap-closing loop and feeds multi-terminal task sets directly into `buildDag`.
- **`GoalStore.unionBuildPicks`** — single atomic write that merges relicGoals/areaGoals/
  pactGoals without burning a pact respec. Build activation never touches `selectedPactIds`.
- **`TaskRepository.findByTargetItemId`** — in-memory lookup on the already-loaded task
  list, keyed by `wikiItemId`.
- **`GearRepository`** — loads `gear.json` from classpath (DB-backed fallback).
  25 items covering Bandos, Armadyl, Barrows Gloves, Fire/Infernal Cape, AoT, Occult,
  Twisted Bow, Dragon Arrows, Primordial/Eternal/Pegasian boots.
- **`GearSlot` enum + `GearItem` + `Build` data models.**
- **`DatabaseSeeder`** — copies bundled `leagues-vi-tasks.db` to
  `~/.runelite/leagues-ai/data/` on first startup so friends don't need to run the scraper.
- **`SettingsPanel` rescrape button** — triggers an in-process re-scrape on demand.
- **`ProximityOptimizer`** — relic-aware nearest-neighbour task pathing. Replaces naive
  linear task order with a graph walk that minimises travel between task locations,
  accounting for relic teleport options.
- **`VectorIndex` item embeddings** — extends the vector index to cover item descriptions
  alongside tasks, enabling "find tasks that grant X item" semantic search.
- **`TaskItemExtractor` + `ItemStatsScraper`** (scraper module) — post-processes scraped
  task names/descriptions to extract equipment targets and populate `tasks.target_items`.
  Verb allowlist: equip/obtain/acquire/wear/wield (no false positives on diary verbs).
- **`ChatService.cancelPendingPlan`** — atomic `planGeneration` increment that invalidates
  in-flight chat plans when a build is activated, preventing stale-plan races.
- **`ItemDependencyGraph`** — in-memory BFS over the `item_dependencies` SQLite table.
  `expand(itemId)` returns all dependency rows in prerequisite-first order (depth-capped
  at 20, cycle-safe). `knownNames()` exposes the name-to-id key set; `findLongestMatchingItem(phrase)`
  pre-sorts names longest-first so O(k) break-on-first match replaces a full-set scan.
  Loaded on a background thread; `volatile` fields guarantee cross-thread visibility to
  EDT reads via `expand()`/`findItemByName()`/`findLongestMatchingItem()`.
- **`ObtainMethod` enum** — SMITHED / CRAFTED / FLETCHED / HERBLORE / COOKED (recursable
  into ingredient chains) vs DROPPED / QUEST_REWARD / SHOP / SKILL_REWARD (terminal leaf).
- **`ItemDependency` model** (`@Value @Builder`) — itemId, itemName, obtainMethod, sourceId,
  sourceName, skillRequired, skillLevel, qtyNeeded, outputQty, areaRequired.
- **`ItemDependencyScraper`** (scraper module) — parses the MediaWiki Lua Module:
  `Obtaining` pages via 3-retry backoff, writes rows into `item_dependencies` table.
- **`ChatHistoryStore`** — persists chat history across plugin restarts at
  `~/.runelite/leagues-ai/data/chat_history.json`. Same atomic write pattern as GoalStore.
- **`GoalType.ITEM`** + **`GoalSpecParser` item detection arm** — keyword-gated (`get` /
  `need` / `want` / `farm` / `craft` / `obtain` and natural-language variants) uses
  `findLongestMatchingItem()` to pick the longest matching item name in the phrase and
  resolves it to a `GoalSpec{type=ITEM}`. `ChatService.maybeTriggerPlanner` is also gated
  on the same check so item-intent phrases trigger goal planning without `/plan` slash
  commands.
- **`GoalPlanner.resolveItemGoal`** — BFS-expands the item dep graph, maps source tasks
  against the live task list, filters by completion/skill/area, returns a `CompositeGoal`.

### Changed

- **`GoalPlanner`** — BUILD branch added to `resolveCompositeGoal`; bypasses gap-closing
  loop, feeds terminal task IDs into existing `buildDag` + `topologicalSort`.
- **`PromptBuilder`** — new `buildGearContext()` method + 4-arg `buildSystemPrompt`
  overload that includes target gear in the system prompt.
- **`WikiScraper.TASK_PAGES`** — now targets `Demonic_Pacts_League/Tasks` (was
  Trailblazer Reloaded). Scraper is ready for 2026-04-15 launch day.
- **`LeaguesAiPlugin.activateBuild`** — returns `boolean` (true=success, false=exception)
  so the toast only fires on genuine success. Guards against `buildExpander == null` when
  DB is still loading.

### Fixed

- `GearRepository`: `GearSlot.valueOf()` crash on unrecognized DB slot strings wrapped
  in try-catch with `log.warn`. Bad rows are skipped cleanly, not silently swallowed.
- `BuildsPanel.executor` (`ExecutorService`) now shut down in `LeaguesAiPlugin.shutDown()`
  via `BuildsPanel.shutdown()`.
- `BuildStore`: hardcoded `"max 5 builds per file"` string replaced with constant reference.
- `LeaguesAiPlugin.activateBuild`: bare Swing mutations on the `llmExecutor` thread wrapped
  in `SwingUtilities.invokeLater` — prevents EDT violations on build activation.
- `LeaguesAiPlugin`: `chatHistoryStore` was dropped when `ChatService` was rebuilt on
  Codex OAuth and API-key change paths. Now set via `chatService.setHistoryStore(...)` in
  both rebuild branches so history survives re-authentication.
- `GoalStore.clearAllGoals`: nulls `currentGoalText` and `currentPlanTaskIds` so a cleared
  goal queue no longer restores a ghost plan on the next plugin restart.
- `GoalPlanner.resolveItemGoal`: N+1 query fixed — `taskRepo.getAllTasks()` now hoisted
  outside the dependency loop (was one DB round-trip per dep; now one total).
- `ProximityOptimizer`: level assignment replaced with a convergence loop (Bellman-Ford
  style) — single-pass was assigning `level=0` to prereqs that appeared later in the
  step list, producing wrong topological ordering.
- `LeaguesAiPlugin.startUp`: `setupPanelCallbacks()` now runs before `loadDatabaseAsync`
  is submitted — fixes a race where `restoreSavedSession` fired before callbacks were
  wired and the restored plan was silently dropped.
- `LeaguesAiPlugin.activateBuild`: now routed through `llmExecutor` — serialises build
  activation with in-flight chat plans, preventing a race that could overwrite an active
  plan mid-stream.
- `LeaguesAiPlugin.activateBuild`: null guard on `finalSteps.get(0)` — no longer crashes
  when the planner returns an empty step list (e.g. all gear tasks already complete).
- `BuildStore.importFromFile`: guard against empty `builds` array — `{"builds":[]}` was
  crashing with `IndexOutOfBoundsException`; now throws a readable `IllegalArgumentException`.
- `BuildStore.persist`: returns `boolean`; `importFromFile` throws if the write fails so
  callers get an explicit error instead of silent data loss.
- `ChatHistoryStore`: swallowed `IOException` on save now logged via `@Slf4j` `warn`.

---

## [Unreleased] — Phase 2, PR 2: Chained Goal Planner

The Phase 1 "Set as goal" button was a no-op because the planner was a flat
keyword matcher. This release makes it actually produce a plan end-to-end.

### Added

- **`GoalSpec` + `GoalType` + `GoalSpecParser`** — a typed goal model
  (`RELIC | AREA | PACT | TASK_BATCH | FREEFORM`) and a regex-driven parser that
  recognises phrases like `"plan unlock the Grimoire relic"`, looks the target
  up in the repo (fuzzy exact-then-substring), and returns a resolved
  `GoalSpec` with the real unlock cost attached.
- **`CompositeGoal` + `GoalPlanner.resolveCompositeGoal`** — given a goal spec
  and the player context, compute the league-point gap, filter
  `taskRepo.getAllTasks()` to tasks the player can actually do (skills met,
  area unlocked, not completed), sort by points-per-effort, greedy-pick until
  the gap is closed. Unreachable targets emit a child `AREA` goal pointing at
  the locked area that would contribute the most points.
- **Relics / Areas / Pacts reference sections in `PromptBuilder`** — every
  chat system prompt now includes the full unlockables list with costs plus
  the "up to 40 pacts, 3 full respecs" doctrine sentence, so the LLM can
  answer "what do I need to unlock Grimoire?" with real data instead of
  guessing. Sections are omitted entirely when the repo is null (backwards
  compatible with existing tests).
- **`TaskRepository.findRelicByName` / `findAreaByName` / `findPactByName`** —
  exact-match-first, then substring match. Used by the goal spec parser.
- **`TaskNormalizer` skill-name alias table** — `"runecrafting"` from the OSRS
  Wiki is now normalised to `"runecraft"` at scrape time so the planner's
  `Skill.valueOf` resolution works without a separate alias pass downstream.
- **Unknown-cost fallback in `GoalPlanner.resolveCompositeGoal`** — when a
  relic or area has `unlockCost == 0` (the wiki hasn't published costs yet
  pre-launch), the resolver returns the top-10 highest-value achievable tasks
  as a fallback suggestion instead of short-circuiting to an empty plan. The
  same code path flips to the cost-driven selector automatically on launch
  day once real costs are scraped.
- **Tests.** 9 new `GoalSpecParser` tests (phrase shapes, fuzzy lookup,
  null-repo fallthrough), 9 new `CompositeGoalResolver` tests (pact
  short-circuit, already-affordable, greedy gap close, unreachable area
  child, completed / locked / skill filtering, lowercase scraper keys,
  unknown-skill fail-closed, unknown-cost top-N fallback, unknown-cost
  no-achievable-tasks), 4 new `PromptBuilder` tests (relics / areas / pacts
  sections, null-repo omission), 2 new `TaskNormalizer` tests (runecrafting
  alias, known skills pass-through).
- **README with Mermaid architecture diagram.**
- **Expanded `.gitignore`** covering IntelliJ / VSCode / Eclipse IDE files,
  JVM crash reports, and a defense-in-depth block of secret file patterns
  (`.env`, `*.key`, `*.pem`, `auth.json`, `credentials.json`,
  `local.properties`) so nothing sensitive can land in the public repo.

### Changed

- **`GoalPlanner.skillsMet` fails closed on unknown skill names.** Stale DB
  rows from before the TaskNormalizer alias landed are quietly excluded from
  plans instead of silently passing requirements they shouldn't. Debug log
  names the offending task for traceability.
- **`ChatService.maybeTriggerPlanner`** routes RELIC / AREA / PACT phrases
  through `resolveCompositeGoal` and falls through to the existing flat
  resolver for every other trigger phrase (no existing trigger was removed).
- **`ChatService.sendMessage`** now passes the repo into
  `PromptBuilder.buildSystemPrompt` so the LLM sees the unlockables reference
  sections.
- **Composite goal path reuses the first `contextAssembler.assemble()` call**
  instead of hopping through `ClientThread` twice per planner trigger.
- **Empty step lists skip item source resolution + persona review.** Pact
  goals and already-affordable goals no longer burn two LLM calls on an
  empty plan.

### Fixed

- **"Set as goal" buttons in `UnlockablesPanel` are no longer no-ops.** Every
  relic, area, and pact row now produces a real chained plan through the
  composite resolver.
- **Runecrafting-gated tasks are no longer silently recommended** to players
  who don't have the level. The `"runecrafting"` wiki key was previously
  failing `Skill.valueOf` resolution and falling through a catch block, which
  meant the requirement check passed for every player. Both layers (scraper
  alias + planner fail-closed) fixed.

---

## [Phase 1] — 2026-04-08 — Demonic Pacts Goal Picker + Scraper

### Added

- **`DemonicPactsScraper`** — standalone scraper for
  `oldschool.runescape.wiki/w/Demonic_Pacts_League/{Relics,Areas,Demonic_Pacts}`.
  Writes to the existing SQLite database alongside the TBZ task scraper. Runs
  via `./scraper/scrape.sh`.
- **`Pact` data model** + `pacts` SQLite table with `parent_id` and
  `unlock_requires` columns reserved for the Phase 2 unlock tree.
- **`UnlockablesPanel`** — accordion goal picker at the top of the Goals tab
  showing relics (grouped by tier), areas (split into universal Varlamore +
  Karamja and 8 unlockable regions), and pacts (flat list, phase 1).
- **`GoalStore`** — JSON-persisted user state for pinned relic/area/pact goals
  at `~/.runelite/leagues-ai/data/goals.json`. Atomic writes via
  temp-file-plus-rename, corruption-safe on malformed JSON.
- **`HtmlParser.parseRelicsPage` / `parseAreasPage` / `parsePactsPage`** — each
  with a per-row try/catch so one malformed row can't nuke the whole load.

### Changed

- **`DatabaseLoader.parseStringObjectMap`** now tolerates plain-text values in
  the relics `effects` column (the phase 1 scraper writes flattened bullet
  text, not JSON). Regression test added in `SchemaContractTest`.
- **`DatabaseLoader.loadRelics` / `loadAreas` / `loadPacts`** wrap per-row
  parsing in try/catch so one bad row can't kill the entire load.

### Fixed

- **`UnlockablesPanel` row alignment.** Classic Swing `BoxLayout.Y_AXIS`
  centers children by default; every container in the column now has
  `LEFT_ALIGNMENT` set explicitly so content stops drifting right.
- **`ChatPanel` and `GoalsPanel` heartbeat labels** wrap in HTML with an
  explicit 195px width hint so multi-word text like "Looking good, take a
  quick break?" no longer gets clipped by the ~210px side panel.
- **Goals tab scroll behaviour.** Restructured to a single outer `JScrollPane`
  wrapping the full center column so unlockables, plan, and empty state
  scroll together. Horizontal scroll policy set to `AS_NEEDED` as a safety
  net for edge cases where content exceeds the viewport width.

---

## [Earlier] — Phase 0

Everything before Phase 1. See `git log` for details. Highlights:

- Goals panel + ironman coach doctrine + heartbeat ticker (`6530a18`)
- Devil emoji icon, scraper without API key, rich task parser (`8cdb600`)
- Inventory in prompt + chat bubbles + auth-gated UI (`2749099`)
- ChatGPT OAuth support via CodexOauthClient (`088fe40`)
- Standalone wiki scraper tool (`8fb0671`)
- Quest Helper overlay ports (`MinimapOverlay`, `ArrowOverlay`, `PathOverlay`,
  `WorldMapOverlay`)

---

## Launch-day TODO (2026-04-15)

Tracked in `CLAUDE.md` under "Phase 2 TODO":

- Swap `WikiScraper.TASK_PAGES` from Trailblazer Reloaded to Demonic Pacts
- Parse the real filter taxonomy from the live page (no hallucinated filter names)
- Capture `items_required` + `skills_required` per task
- Populate `areas.unlock_cost` once the wiki publishes costs
- Populate `pacts.parent_id` + `unlock_requires` if the wiki documents the tree
- Hook `GoalStore.isUnlocked` into a `LeagueStatusMonitor` for real in-game state
- ~~Tiered pacts UI with 40-slot budget + 3-respec tracker~~ — shipped in PR 3 (`GoalStore.selectPact` / `deselectPact` / `resetPacts`, `MAX_PACT_SLOTS=40`, `MAX_RESPECS=3`)
