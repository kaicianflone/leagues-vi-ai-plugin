# Leagues VI AI Plugin — Design Spec

**Date:** 2026-04-05
**Status:** Approved
**Timeline:** 2 weeks to MVP, then 2 months iteration during the league
**Distribution:** Friends-only (no public Plugin Hub release)

## Overview

A modular RuneLite plugin that acts as an AI-powered Leagues VI (Demonic Pacts) coach. It monitors player state in real time, plans goal-oriented task sequences optimized by area proximity and difficulty tier, renders Quest Helper-parity overlays guiding the player through each step, and provides a chat/advice interface powered by OpenAI. The side panel features an animated ASCII sprite character that changes animation based on the current activity.

## Architecture

Six modules inside one RuneLite plugin .jar, plus a standalone scraper tool.

```
+------------------------------------------------------------------+
|                    RuneLite Client                                 |
|                                                                   |
|  +----------+  +----------+  +-----------+  +----------------+   |
|  |   Core   |  |   Data   |  |   Agent   |  |    Overlay     |   |
|  |          |  |          |  |           |  |                |   |
|  | Config   |<>| SQLite   |<>| OpenAI    |  | Arrows         |   |
|  | EventBus |  | Cache    |  | Planner   |--| Highlights     |   |
|  | Lifecycle|  | Models   |  | Context   |  | Pathing        |   |
|  | Monitors |  | Vectors  |  | Chat      |  | Minimap        |   |
|  +----+-----+  +----------+  +-----------+  +----------------+   |
|       |                                                           |
|  +----+------------------------------------------------------+   |
|  |                        UI Module                           |   |
|  |  +--------------+  +--------+  +--------+  +----------+  |   |
|  |  | ASCII Sprite |  |  Chat  |  | Advice |  | Settings |  |   |
|  |  |  Renderer    |  | Panel  |  | Panel  |  |  Panel   |  |   |
|  |  +--------------+  +--------+  +--------+  +----------+  |   |
|  +------------------------------------------------------------+   |
+-------------------------------------------------------------------+

+---------------------+         +------------------+
| Scraper (standalone) |------->| leagues-vi.db    |
| gstack browse        |        | (SQLite + blobs) |
| Wiki parser          |        +------------------+
+---------------------+
```

## Module 1: Core

Plugin entry point, configuration, event bus, and background monitors.

### Config

All stored via RuneLite's `ConfigManager`:

| Key | Type | Description |
|-----|------|-------------|
| `openaiApiKey` | String | User's OpenAI API key (password masked in UI) |
| `autoMode` | boolean | Whether agent auto-advances to next task |
| `currentGoal` | String | Active goal, persisted between sessions |
| `overlayColor` | Color | Highlight color for overlays |
| `animationSpeed` | int | ASCII sprite frame interval in ms (default 200) |

### Event Bus

Simple in-process pub/sub. No external dependencies.

```java
public interface EventBus {
    <T> void subscribe(Class<T> eventType, Consumer<T> handler);
    <T> void publish(T event);
}
```

Implementation: `Map<Class<?>, List<Consumer<?>>>`. All handlers fire synchronously on the client thread.

### Monitors

| Monitor | Interval | Events Fired | Data Source |
|---------|----------|-------------|-------------|
| XP Monitor | 60s | `XpGainEvent(skill, delta, total)` | `Client.getSkillExperience()` |
| Task Monitor | 300s | `TaskCompletedEvent(taskId)`, `TaskProgressEvent(taskId, progress)` | Leagues varbit/widget inspection |
| Inventory Monitor | Per tick, debounced | `InventoryStateEvent(items[])` | RuneLite `InventoryChanged` |
| Skill Monitor | On level up + 60s | `SkillStateEvent(levels[], boostedLevels[])` | `Client.getRealSkillLevel()` |
| Location Monitor | Per tick, debounced | `LocationEvent(regionId, worldPoint)` | `Client.getLocalPlayer()` |
| Area Unlock Monitor | Per tick | `AreaUnlockedEvent(areaName)` | Leagues area varbit changes |

## Module 2: Data

Local SQLite database for all reference data. In-memory vector index for chat semantic search.

### SQLite Schema

```sql
CREATE TABLE tasks (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    description     TEXT,
    difficulty      TEXT CHECK(difficulty IN ('easy','medium','hard','elite','master')),
    points          INTEGER,
    area            TEXT,
    category        TEXT,
    skills_required TEXT,    -- JSON: {"cooking": 50, "fishing": 40}
    quests_required TEXT,    -- JSON: ["Cook's Assistant"]
    tasks_required  TEXT,    -- JSON: ["task-uuid-1", "task-uuid-2"]
    items_required  TEXT,    -- JSON: {"harpoon": 1}
    location        TEXT,    -- JSON: {"region": 12850, "x": 3213, "y": 3428, "plane": 0}
    target_npcs     TEXT,    -- JSON: [{"id": 1234, "name": "Goblin"}]
    target_objects   TEXT,    -- JSON: [{"id": 5678, "name": "Cooking range"}]
    target_items    TEXT,    -- JSON: [{"id": 315, "name": "Shrimps"}]
    wiki_url        TEXT,
    embedding       BLOB     -- float[] serialized, 1536 dimensions
);

CREATE TABLE areas (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    unlock_cost     INTEGER,
    unlock_requires TEXT,    -- JSON: ["misthalin"]
    region_ids      TEXT     -- JSON: [12850, 12851, ...]
);

CREATE TABLE relics (
    id              TEXT PRIMARY KEY,
    name            TEXT,
    tier            INTEGER,
    description     TEXT,
    unlock_cost     INTEGER,
    effects         TEXT     -- JSON
);

CREATE TABLE locations (
    name            TEXT PRIMARY KEY,
    world_x         INTEGER,
    world_y         INTEGER,
    plane           INTEGER,
    region_id       INTEGER,
    aliases         TEXT     -- JSON: ["lumby", "lumbridge"]
);
```

### Cache Layer

- **On plugin start:** bulk load all tasks, areas, relics into memory maps
- **Refresh:** every 30 minutes or manual refresh button. Hot-reload from .db file.
- **Player state is never stored in SQLite.** Read from RuneLite client only.

### In-Memory Vector Index

- On startup, load all task embeddings from SQLite BLOB column into a `float[][]` array
- Cosine similarity search in Java. Brute force. 2-5k tasks at 1536 dimensions takes <1ms.
- Used only for chat feature: user query -> OpenAI embeddings API -> cosine search -> top-K relevant tasks -> stuff into LLM prompt

```java
public interface TaskRepository {
    List<Task> getAllTasks();
    List<Task> searchSimilar(float[] queryEmbedding, int limit);
    Task getById(String id);
    List<Task> getByArea(String area);
    List<Task> getByDifficulty(Difficulty difficulty);
    List<Task> getPrerequisites(String taskId);  // recursive
}
```

### Database File Location

The scraper outputs `leagues-vi-tasks.db`. It lives at `~/.runelite/leagues-ai/data/leagues-vi-tasks.db`. Plugin reads from there on startup. To update mid-league: re-run scraper, replace file, restart plugin (or hit "Refresh Data" in settings).

## Module 3: Agent

OpenAI integration, goal-oriented planning, chat, and advice.

### Player Context

Assembled fresh for every LLM call:

```java
public class PlayerContext {
    Map<Skill, Integer> levels;
    Map<Skill, Integer> xp;
    List<Integer> inventory;
    List<Integer> equipment;
    Set<String> completedTasks;
    Set<String> unlockedAreas;
    WorldPoint location;
    int leaguePoints;
    int combatLevel;
    String currentGoal;
    List<Task> currentPlan;
}
```

Full context dump to the LLM every call. At friends-scale, token cost is not a concern.

### Goal-Oriented Planner

Input: a goal string (e.g., "unlock Morytania", "complete all easy tasks in Misthalin", "get 99 cooking")

Algorithm:
1. **Resolve goal to target tasks** from SQLite. Natural language goal -> filtered task query + LLM disambiguation if needed.
2. **Recursively resolve prerequisites.** Each target task may require skills, quests, or other tasks. Walk the dependency tree.
3. **Build a DAG** of all required tasks (completed tasks pruned).
4. **Topological sort** the DAG.
5. **Optimize ordering** using heuristics:
   - Proximity: group tasks in the same area to minimize travel
   - Difficulty tier: easy -> medium -> hard within an area (faster point accumulation for unlocks/relics)
   - Skill batching: consecutive tasks needing the same skill grouped together
   - Current state: skip satisfied requirements, prioritize tasks closest to completion
6. **LLM refinement:** send the computed plan to OpenAI for review. Prompt: "Here's my computed plan for [goal]. Player state: [context]. Optimize for efficiency, flag any issues, suggest reordering."
7. **Output:** ordered `List<PlannedStep>`

```java
public class PlannedStep {
    Task task;
    WorldPoint destination;
    List<Integer> requiredItems;
    String instruction;          // "Fish 50 shrimp at Lumbridge"
    OverlayData overlayData;     // what to highlight in-game
    AnimationType animation;     // which ASCII sprite to show
}
```

### Re-planning Triggers

| Trigger | Action |
|---------|--------|
| Task completed | Remove from plan, advance to next step |
| Off-script 2+ minutes | Re-plan from current state and location |
| Level up unlocks new routes | Re-plan to incorporate efficient new tasks |
| New goal set by user | Full re-plan |
| Area unlocked | Re-plan to incorporate new area tasks |

### Chat Interface

- OpenAI chat completions API (gpt-4o model, configurable in settings)
- System prompt includes: player context, current plan, relevant tasks from vector search, persona as a Leagues coach
- Conversation history: last 20 messages, kept in memory
- User types in chat panel, response streams back via SSE

### Advice (One-Shot)

- Triggered by "Advice" button
- Assembles context + plan state
- Single prompt: "Given this player's state and plan progress, what should they focus on next and why?"
- Returns 2-3 paragraph response, displayed in advice panel
- No conversation history, fresh each time

## Module 4: Overlay

Full Quest Helper parity. Each overlay type is a separate class. TDD mandatory with 100% test coverage.

### Overlay Types

| Class | Renders | QH Equivalent |
|-------|---------|---------------|
| `ArrowOverlay` | 3D arrow pointing down at target tile | `QuestArrowOverlay` |
| `TileHighlightOverlay` | Colored tile marker on destination | `QuestTileOverlay` |
| `NpcHighlightOverlay` | Outline/hull around target NPCs by ID | NPC indicator |
| `ObjectHighlightOverlay` | Highlight interactive game objects | Object highlight |
| `GroundItemOverlay` | Highlight specific items on the ground | Item highlights |
| `MinimapOverlay` | Arrow/dot on minimap toward target | `QuestMinimapOverlay` |
| `WorldMapOverlay` | Pin + path on world map | `QuestWorldMapPoint` |
| `PathOverlay` | Walking path line from player to destination | Path rendering |
| `WidgetOverlay` | Highlight UI elements (spellbook, prayers, inventory slots) | Widget highlights |

### Overlay Controller

```java
public class OverlayController {
    void setActiveStep(PlannedStep step);  // activates relevant overlays
    void clearAll();
    void setHighlightColor(Color color);
}
```

A single `PlannedStep` may activate multiple overlays simultaneously. Example for "Fish shrimp at Lumbridge":
- `TileHighlightOverlay` on the fishing spot
- `NpcHighlightOverlay` on the fishing spot NPC
- `MinimapOverlay` arrow pointing toward spot
- `PathOverlay` showing walk path
- `WorldMapOverlay` with a pin

### TDD Requirements

Each overlay class tested against mock `Client`, `Graphics2D`, and `WorldPoint`:

- Correct rendering coordinates (world-to-screen projection)
- Overlay only renders when target is in viewport
- Overlay deactivates when step changes
- Color/style matches configuration
- Multiple overlays don't conflict
- Null safety (NPC despawned, object not loaded, region not loaded)
- Region boundary behavior (target in different region)
- Edge cases: player on different plane, target behind camera, minimap rotation

## Module 5: UI

RuneLite `PluginPanel` side panel.

### Panel Layout (top to bottom)

1. **ASCII Sprite Display** — `JPanel` with monospace font rendering, centered
2. **Status Bar** — current task name, progress counter (X/Y tasks)
3. **Tab Buttons** — Chat | Advice | Settings (toggle active tab)
4. **Tab Content Area** — swaps based on active tab

### ASCII Sprite Renderer

```java
public class AsciiSpriteRenderer extends JPanel {
    void setAnimation(AnimationType type);
    void pause();
    void resume();
}
```

- Each animation: list of `String[]` frames
- `javax.swing.Timer` cycles frames at configurable interval (default 200ms, ~5 FPS)
- Animations stored as resource files: `sprites/walking.txt`, `sprites/melee.txt`, etc.
- Frame format: frames separated by `---` delimiter

### Animations (10 total, extensible)

| AnimationType | Trigger Condition |
|---------------|-------------------|
| `IDLE` | No active task |
| `WALKING` | Traveling to next task location |
| `BANKING` | At a bank, managing inventory |
| `MELEE` | Combat task + melee weapon equipped |
| `RANGED` | Combat task + ranged weapon equipped |
| `MAGE` | Combat task + magic weapon/runes equipped |
| `COOKING` | Cooking task active + near range |
| `FISHING` | Fishing task active + at fishing spot |
| `WOODCUTTING` | Woodcutting task active + at tree |
| `MINING` | Mining task active + at rock |

Animation selection logic: Location monitor + equipment monitor + current task category -> `AnimationType`. Resolved by an `AnimationResolver` class.

### Tab Panels

**Chat Tab:**
- `JScrollPane` with message history
- `JTextField` input at bottom with send button
- Messages render as alternating styled text blocks (user vs agent)
- Last 20 messages displayed

**Advice Tab:**
- `JTextArea` showing latest advice response
- "Refresh" button to re-query
- Current goal display
- Plan progress: X/Y tasks complete
- Next 3 upcoming steps listed

**Settings Tab:**
- OpenAI API key: `JPasswordField`
- Auto-mode toggle: `JCheckBox`
- Current goal: `JTextField` + "Set Goal" button
- Animation speed: `JSlider` (100ms-500ms)
- Overlay color: `JColorChooser` button
- Refresh Data: `JButton` to hot-reload SQLite .db

## Module 6: Scraper (Standalone)

Not part of the plugin .jar. A CLI tool run locally to populate the SQLite database.

### Pipeline

```
Wiki Area Pages -> gstack browse (fetch HTML)
       |
  HTML Parser (extract task tables)
       |
  Task Normalizer (parse requirements, skill/quest names)
       |
  Location Resolver (map descriptions to WorldPoint coordinates)
       |
  Embedding Generator (OpenAI text-embedding-3-small, 1536 dimensions)
       |
  SQLite Writer (upsert into leagues-vi-tasks.db)
```

### Location Resolution

- Maintain a `locations` table mapping common place names to exact coordinates
- Scrape wiki location pages for NPC/object coordinate data
- Ambiguous locations: store multiple candidates, overlay module picks nearest to player at render time

### Pre-build Strategy

- Build and test the full pipeline now against Leagues V (Trailblazer Reloaded) wiki pages
- On Leagues VI announcement: update target URLs and any schema changes
- On launch day: run scraper, generate .db, drop into `~/.runelite/leagues-ai/data/`

### Update Flow

Re-run scraper anytime to refresh data. Replace .db file. Plugin picks it up on restart or via "Refresh Data" button in settings.

## Testing Strategy

### TDD-Mandatory Modules

**Overlay Module (100% coverage required):**
- Every overlay class has unit tests against mock `Client`, `Graphics2D`, `WorldPoint`
- Tests verify rendering coordinates, activation/deactivation, null safety, edge cases
- Integration tests verify overlay combinations don't conflict
- Reference tests against Quest Helper's overlay behavior

**Planner Logic:**
- Unit tests for DAG construction from task prerequisites
- Unit tests for topological sort with proximity/difficulty/skill-batch optimization
- Integration tests with sample task databases
- Edge cases: circular dependencies, impossible goals, all tasks completed

### Standard Testing

**All other modules:** unit tests for public interfaces, integration tests for cross-module communication via event bus.

## File Structure

```
leagues-vi-ai-plugin/
  build.gradle
  settings.gradle
  src/
    main/java/com/leaguesai/
      LeaguesAiPlugin.java          # Entry point
      LeaguesAiConfig.java          # RuneLite config interface
      core/
        EventBus.java
        EventBusImpl.java
        monitors/
          XpMonitor.java
          TaskMonitor.java
          InventoryMonitor.java
          SkillMonitor.java
          LocationMonitor.java
          AreaUnlockMonitor.java
        events/
          XpGainEvent.java
          TaskCompletedEvent.java
          TaskProgressEvent.java
          InventoryStateEvent.java
          SkillStateEvent.java
          LocationEvent.java
          AreaUnlockedEvent.java
      data/
        TaskRepository.java
        TaskRepositoryImpl.java
        VectorIndex.java
        DatabaseLoader.java
        model/
          Task.java
          Area.java
          Relic.java
          Difficulty.java
          Location.java
      agent/
        PlayerContext.java
        PlayerContextAssembler.java
        GoalPlanner.java
        PlannerOptimizer.java
        PlannedStep.java
        OpenAiClient.java
        ChatService.java
        AdviceService.java
        PromptBuilder.java
      overlay/
        OverlayController.java
        OverlayData.java
        ArrowOverlay.java
        TileHighlightOverlay.java
        NpcHighlightOverlay.java
        ObjectHighlightOverlay.java
        GroundItemOverlay.java
        MinimapOverlay.java
        WorldMapOverlay.java
        PathOverlay.java
        WidgetOverlay.java
      ui/
        LeaguesAiPanel.java
        AsciiSpriteRenderer.java
        SpriteAnimation.java
        AnimationType.java
        AnimationResolver.java
        ChatPanel.java
        AdvicePanel.java
        SettingsPanel.java
    main/resources/
      sprites/
        idle.txt
        walking.txt
        banking.txt
        melee.txt
        ranged.txt
        mage.txt
        cooking.txt
        fishing.txt
        woodcutting.txt
        mining.txt
    test/java/com/leaguesai/
      core/
        EventBusImplTest.java
        monitors/
          XpMonitorTest.java
          TaskMonitorTest.java
          InventoryMonitorTest.java
          SkillMonitorTest.java
          LocationMonitorTest.java
          AreaUnlockMonitorTest.java
      data/
        TaskRepositoryImplTest.java
        VectorIndexTest.java
        DatabaseLoaderTest.java
      agent/
        GoalPlannerTest.java
        PlannerOptimizerTest.java
        PlayerContextAssemblerTest.java
        OpenAiClientTest.java
        ChatServiceTest.java
        AdviceServiceTest.java
        PromptBuilderTest.java
      overlay/
        ArrowOverlayTest.java
        TileHighlightOverlayTest.java
        NpcHighlightOverlayTest.java
        ObjectHighlightOverlayTest.java
        GroundItemOverlayTest.java
        MinimapOverlayTest.java
        WorldMapOverlayTest.java
        PathOverlayTest.java
        WidgetOverlayTest.java
        OverlayControllerTest.java
      ui/
        AsciiSpriteRendererTest.java
        AnimationResolverTest.java
  scraper/
    build.gradle (or requirements.txt if Python)
    scrape.sh                       # Run scraper with gstack browse
    src/
      WikiScraper.java
      HtmlParser.java
      TaskNormalizer.java
      LocationResolver.java
      EmbeddingGenerator.java
      SqliteWriter.java
```

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Data storage | Local SQLite | Friends-only, no account setup, ships as one .db file |
| Vector search | In-memory Java cosine similarity | 2-5k tasks, brute force is <1ms, no native extensions needed |
| LLM integration | Direct plugin -> OpenAI API | No middleware needed for friends group |
| Overlay approach | Full Quest Helper parity, TDD | 100% coverage ensures reliability, each overlay class testable in isolation |
| ASCII animations | Frame-based .txt files | Easy to author and extend over 2 months |
| Scraper | Standalone tool, gstack browse | Pre-build against Leagues V, update on launch day |
| Planning | Goal-oriented with proximity + difficulty optimization | Matches how efficient league play works: clear areas by tier, minimize travel |
| Distribution | Direct .jar sharing | Friends-only, no Plugin Hub submission needed |
