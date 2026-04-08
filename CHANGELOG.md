# Changelog

All notable changes to the Leagues VI AI Plugin. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning is informal
pre-launch (2026-04-15); the first tagged release will be cut on or after
launch day.

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
- Tiered pacts UI with 40-slot budget + 3-respec tracker (Phase 2 PR 3)
