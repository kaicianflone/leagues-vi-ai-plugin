# Leagues VI AI Plugin — Project Rules

## What this project is

A RuneLite plugin that acts as an AI-powered Leagues VI coach. Friends-only distribution. Six modules: `core` (event bus + monitors), `data` (SQLite task repository + vector index), `agent` (OpenAI / ChatGPT OAuth client + goal planner), `overlay` (in-game rendering), `ui` (side panel with chat, advice, settings tabs), and a standalone `scraper` subproject that populates the task database from the OSRS Wiki.

## Non-negotiable rules for overlays

**Overlays must maintain 1:1 parity with Quest Helper.** This is a hard requirement, not a guideline.

- Quest Helper is at https://github.com/Zoinkwiz/quest-helper and is BSD-2-Clause licensed. Porting code is allowed and expected.
- Any new overlay or rendering behavior must start by reading the equivalent code in Quest Helper's `src/main/java/com/questhelper/` tree. Find the real implementation first, then port it with attribution.
- Do not hand-roll approximations. If Quest Helper clamps to `getMaxMinimapDrawDistance(client)`, use that. If Quest Helper uses `OverlayUtil.renderPolygon` with a specific stroke, use the same. If Quest Helper's `DirectionArrow.drawArrow` draws a black outline then a colored stroke, do it in that order.
- Every ported file must have the BSD-2-Clause attribution comment block at the top referencing `ATTRIBUTIONS.md` and preserving the original copyright notice.
- When Quest Helper's approach can't compile against our RuneLite API version (e.g., missing constants, renamed interfaces), document the divergence inline with a comment explaining what was changed and why. Never silently depart from the ported behavior.

**Ported overlay files so far:**
- `overlay/MinimapOverlay.java` — from `questhelper/steps/overlay/DirectionArrow.java` + `QuestPerspective.getMinimapPoint`
- `overlay/ArrowOverlay.java` — from `DetailedQuestStep.renderArrow` + `DirectionArrow.drawWorldArrow`
- `overlay/PathOverlay.java` — from `questhelper/steps/overlay/WorldLines.java`
- `overlay/WorldMapOverlay.java` — uses `WorldMapPoint` builder with `snapToEdge(true)` and `jumpOnClick(true)` to mirror `QuestWorldMapPoint`

**Overlays not yet ported from Quest Helper (TODO):**
- `TileHighlightOverlay.java`
- `NpcHighlightOverlay.java` — QH has 3 styles (CONVEX_HULL, OUTLINE, TILE). Port all three.
- `ObjectHighlightOverlay.java` — same three styles
- `GroundItemOverlay.java`
- `WidgetOverlay.java`
- `RequiredItemsOverlay.java` — compare against QH's `QuestHelperPanel` required items section

When a subagent touches any of the above, it MUST port from Quest Helper rather than leave the hand-rolled version in place.

## Non-negotiable rules for tests

**Every overlay ported from Quest Helper must also port the corresponding tests.** Quest Helper has tests for its rendering utilities under `src/test/java/com/questhelper/`. Read them, adapt them to our mocks, and include them in `src/test/java/com/leaguesai/overlay/`.

- Use Mockito with `MockedStatic` for `Perspective`, `LocalPoint`, and other static RuneLite utilities.
- Overlay tests must verify actual draw calls (`graphics.draw(any(Shape.class))`, `graphics.fillPolygon(...)`, `setColor(...)`, `setStroke(...)`). Null-return assertions alone are coverage theater and must not be accepted.
- The existing port of `MinimapOverlayTest` is the reference pattern: stub `client.getMinimapZoom()`, `player.getMinimapLocation()`, `player.getWorldLocation()`, etc., then assert specific draw operations on a spied `Graphics2D`.
- All tests run via `JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home ./gradlew test`. They must pass before any commit.

## Non-negotiable rules for dependency injection

**Every overlay class must be `@Singleton`.** This is not optional. We already hit a production bug where `LeaguesAiPlugin` and `OverlayController` injected different instances of the same overlay — registered one with the OverlayManager but set targets on the other. Nothing rendered. The fix was adding `@Singleton`. Do not remove it. Do not add a new overlay without it.

- `MinimapOverlay`, `ArrowOverlay`, `TileHighlightOverlay`, `NpcHighlightOverlay`, `ObjectHighlightOverlay`, `GroundItemOverlay`, `PathOverlay`, `WidgetOverlay`, `WorldMapOverlay`, `RequiredItemsOverlay`, `OverlayController` — all `@Singleton`.
- Any overlay added in the future: `@Singleton`.

## Runtime environment

- Java 11 required. `JAVA_HOME` must point at `/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home`.
- Use `./gradlew runPlugin` to launch RuneLite with the plugin loaded (not `./gradlew run` — that would also trigger `:scraper:run` which needs args and fails the build).
- Scraper: `./scraper/scrape.sh` (no API key needed; embeddings are skipped).
- SQLite database lives at `~/.runelite/leagues-ai/data/leagues-vi-tasks.db`. Regenerate by re-running the scraper.

## Authentication

The plugin supports two LLM auth modes, auto-detected:

1. **ChatGPT OAuth** (preferred) — if `~/.codex/auth.json` exists, the plugin uses `CodexOauthClient` to talk to `chatgpt.com/backend-api/codex/responses` with the player's ChatGPT Plus credentials. Token refresh is automatic on 401.
2. **Platform API key** — fallback. Set via Settings tab. Uses `OpenAiClient` against `api.openai.com/v1/chat/completions`.

The Settings panel hides itself post-authentication; only Chat and Advice tabs are visible once signed in.

## Debug logging policy

**Heavy debug logging is enabled on purpose for the first couple weeks.** The user and their friends are actively shaking out issues. Do NOT remove the `MINIMAP DEBUG` log lines in `MinimapOverlay.java` or the `CODEX DEBUG request body:` lines in `CodexOauthClient.java`. They are intentional and will be stripped later.

## Planner trigger policy

Natural-language phrases in the chat should trigger `GoalPlanner` without requiring `/plan` slash commands. See `ChatService.maybeTriggerPlanner` for the current phrase list. When a plan is built, `ChatService.onPlanCreated` fires and the plugin's callback pushes the plan into:
- Chat panel banner (`panel.getChatPanel().setActivePlan(...)`)
- Advice panel (goal / progress / next steps)
- `OverlayController.setActiveStep(firstStep)` — activates all overlays for the first task

When the overlay chain doesn't fire, check:
1. Is the trigger phrase detected? Look for `Planner triggered by message:` in the log.
2. Is the callback firing? Look for `Plan callback: activating first step`.
3. Is the overlay actually rendering? Look for the overlay's `DEBUG` log lines (e.g., `MINIMAP DEBUG target=... branch=...`).
4. If render is firing but nothing shows, the branch taken will tell you where the rendering pipeline is bailing.

## Where to look when something breaks

- **Plugin doesn't start:** check Guice injection errors in the RuneLite log. Most commonly caused by a missing `@Singleton` or a service that depends on `TaskRepository` (which is built manually, not Guice-injected).
- **Plan doesn't load:** check `ChatService.maybeTriggerPlanner` — it requires `taskRepo` + `goalPlanner` to be non-null.
- **Overlay doesn't render:** check the singleton rule above. Check the debug log branch.
- **Chat returns 400 "Unsupported content type":** the Codex backend is strict about `Content-Type` header. Must be exactly `application/json` (no charset parameter). See `CodexOauthClient.JSON` constant.
