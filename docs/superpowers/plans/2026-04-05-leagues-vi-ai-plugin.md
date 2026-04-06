# Leagues VI AI Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a modular RuneLite plugin that acts as an AI-powered Leagues VI coach with goal-oriented planning, Quest Helper-parity overlays, animated ASCII sprites, and a chat/advice interface powered by OpenAI.

**Architecture:** Six Java modules (core, data, agent, overlay, ui, scraper) inside one RuneLite plugin .jar. Uses Guice DI, RuneLite's @Subscribe event system for game events, local SQLite for task data, in-memory vector index for semantic search, and direct OpenAI API calls. Standalone scraper tool populates the SQLite database from OSRS Wiki.

**Tech Stack:** Java 11, Gradle, RuneLite API (latest.release), Lombok, SQLite (JDBC), OkHttp (OpenAI calls), JUnit 4, Mockito, Guice

**Spec:** `docs/superpowers/specs/2026-04-05-leagues-vi-ai-plugin-design.md`

---

## File Structure

```
leagues-vi-ai-plugin/
  build.gradle
  settings.gradle
  src/
    main/java/com/leaguesai/
      LeaguesAiPlugin.java
      LeaguesAiConfig.java
      core/
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
          InventoryStateEvent.java
          SkillStateEvent.java
          LocationChangedEvent.java
          AreaUnlockedEvent.java
          PlanUpdatedEvent.java
      data/
        DatabaseLoader.java
        TaskRepository.java
        TaskRepositoryImpl.java
        VectorIndex.java
        model/
          Task.java
          Area.java
          Relic.java
          Difficulty.java
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
      icon.png
    test/java/com/leaguesai/
      core/
        monitors/
          XpMonitorTest.java
          TaskMonitorTest.java
          InventoryMonitorTest.java
          SkillMonitorTest.java
          LocationMonitorTest.java
          AreaUnlockMonitorTest.java
      data/
        DatabaseLoaderTest.java
        TaskRepositoryImplTest.java
        VectorIndexTest.java
      agent/
        GoalPlannerTest.java
        PlannerOptimizerTest.java
        PlayerContextAssemblerTest.java
        OpenAiClientTest.java
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
        SpriteAnimationTest.java
        AnimationResolverTest.java
  scraper/
    build.gradle
    scrape.sh
    src/main/java/com/leaguesai/scraper/
      WikiScraper.java
      HtmlParser.java
      TaskNormalizer.java
      LocationResolver.java
      EmbeddingGenerator.java
      SqliteWriter.java
    src/test/java/com/leaguesai/scraper/
      HtmlParserTest.java
      TaskNormalizerTest.java
      LocationResolverTest.java
```

---

## Task 1: Project Scaffolding

**Files:**
- Create: `build.gradle`
- Create: `settings.gradle`
- Create: `src/main/java/com/leaguesai/LeaguesAiPlugin.java`
- Create: `src/main/java/com/leaguesai/LeaguesAiConfig.java`
- Create: `src/test/java/com/leaguesai/LeaguesAiPluginTest.java`

- [ ] **Step 1: Create settings.gradle**

```gradle
rootProject.name = 'leagues-vi-ai-plugin'
include 'scraper'
```

- [ ] **Step 2: Create build.gradle**

```gradle
plugins {
    id 'java'
}

repositories {
    mavenLocal()
    maven {
        url = 'https://repo.runelite.net'
        content {
            includeGroupByRegex("net\\.runelite.*")
        }
    }
    mavenCentral()
}

def runeLiteVersion = 'latest.release'

dependencies {
    compileOnly group: 'net.runelite', name: 'client', version: runeLiteVersion

    compileOnly 'org.projectlombok:lombok:1.18.30'
    annotationProcessor 'org.projectlombok:lombok:1.18.30'

    implementation 'org.xerial:sqlite-jdbc:3.45.1.0'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.google.code.gson:gson:2.10.1'

    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.mockito:mockito-core:5.10.0'
    testImplementation group: 'net.runelite', name: 'client', version: runeLiteVersion
    testImplementation group: 'net.runelite', name: 'jshell', version: runeLiteVersion
}

group = 'com.leaguesai'
version = '1.0-SNAPSHOT'

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.release.set(11)
}

test {
    useJUnit()
}
```

- [ ] **Step 3: Create LeaguesAiConfig.java**

```java
package com.leaguesai;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import java.awt.Color;

@ConfigGroup("leaguesai")
public interface LeaguesAiConfig extends Config
{
    @ConfigSection(
        name = "API Settings",
        description = "LLM API configuration",
        position = 0
    )
    String apiSection = "apiSettings";

    @ConfigSection(
        name = "Display Settings",
        description = "Overlay and UI configuration",
        position = 1
    )
    String displaySection = "displaySettings";

    @ConfigItem(
        keyName = "openaiApiKey",
        name = "OpenAI API Key",
        description = "Your OpenAI API key for chat and planning features",
        position = 0,
        section = apiSection,
        secret = true
    )
    default String openaiApiKey()
    {
        return "";
    }

    @ConfigItem(
        keyName = "openaiModel",
        name = "OpenAI Model",
        description = "Which model to use for chat and planning",
        position = 1,
        section = apiSection
    )
    default String openaiModel()
    {
        return "gpt-4o";
    }

    @ConfigItem(
        keyName = "autoMode",
        name = "Auto Mode",
        description = "Automatically advance to next planned task on completion",
        position = 2
    )
    default boolean autoMode()
    {
        return true;
    }

    @ConfigItem(
        keyName = "currentGoal",
        name = "Current Goal",
        description = "The active goal the planner is working toward",
        position = 3
    )
    default String currentGoal()
    {
        return "";
    }

    @ConfigItem(
        keyName = "overlayColor",
        name = "Overlay Color",
        description = "Color for tile highlights and arrows",
        position = 0,
        section = displaySection
    )
    default Color overlayColor()
    {
        return Color.CYAN;
    }

    @ConfigItem(
        keyName = "animationSpeed",
        name = "Animation Speed (ms)",
        description = "Frame interval for ASCII sprite animation in milliseconds",
        position = 1,
        section = displaySection
    )
    default int animationSpeed()
    {
        return 200;
    }
}
```

- [ ] **Step 4: Create LeaguesAiPlugin.java (minimal shell)**

```java
package com.leaguesai;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
    name = "Leagues AI",
    description = "AI-powered Leagues VI coach with goal planning, overlays, and chat",
    tags = {"leagues", "ai", "coach", "tasks", "overlay"}
)
public class LeaguesAiPlugin extends Plugin
{
    @Inject
    private LeaguesAiConfig config;

    @Override
    protected void startUp() throws Exception
    {
        log.info("Leagues AI plugin started");
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("Leagues AI plugin stopped");
    }

    @Provides
    LeaguesAiConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LeaguesAiConfig.class);
    }
}
```

- [ ] **Step 5: Create test launcher**

```java
package com.leaguesai;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LeaguesAiPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(LeaguesAiPlugin.class);
        RuneLite.main(args);
    }
}
```

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add build.gradle settings.gradle src/
git commit -m "feat: project scaffolding with RuneLite plugin shell and config"
```

---

## Task 2: Data Models

**Files:**
- Create: `src/main/java/com/leaguesai/data/model/Difficulty.java`
- Create: `src/main/java/com/leaguesai/data/model/Task.java`
- Create: `src/main/java/com/leaguesai/data/model/Area.java`
- Create: `src/main/java/com/leaguesai/data/model/Relic.java`

- [ ] **Step 1: Create Difficulty enum**

```java
package com.leaguesai.data.model;

public enum Difficulty
{
    EASY(1),
    MEDIUM(2),
    HARD(3),
    ELITE(4),
    MASTER(5);

    private final int tier;

    Difficulty(int tier)
    {
        this.tier = tier;
    }

    public int getTier()
    {
        return tier;
    }

    public static Difficulty fromString(String s)
    {
        return valueOf(s.toUpperCase());
    }
}
```

- [ ] **Step 2: Create Task model**

```java
package com.leaguesai.data.model;

import lombok.Builder;
import lombok.Data;
import net.runelite.api.coords.WorldPoint;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class Task
{
    private final String id;
    private final String name;
    private final String description;
    private final Difficulty difficulty;
    private final int points;
    private final String area;
    private final String category;
    private final Map<String, Integer> skillsRequired;
    private final List<String> questsRequired;
    private final List<String> tasksRequired;
    private final Map<String, Integer> itemsRequired;
    private final WorldPoint location;
    private final List<NpcTarget> targetNpcs;
    private final List<ObjectTarget> targetObjects;
    private final List<ItemTarget> targetItems;
    private final String wikiUrl;

    @Data
    @Builder
    public static class NpcTarget
    {
        private final int id;
        private final String name;
    }

    @Data
    @Builder
    public static class ObjectTarget
    {
        private final int id;
        private final String name;
    }

    @Data
    @Builder
    public static class ItemTarget
    {
        private final int id;
        private final String name;
    }
}
```

- [ ] **Step 3: Create Area model**

```java
package com.leaguesai.data.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class Area
{
    private final String id;
    private final String name;
    private final int unlockCost;
    private final List<String> unlockRequires;
    private final List<Integer> regionIds;
}
```

- [ ] **Step 4: Create Relic model**

```java
package com.leaguesai.data.model;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class Relic
{
    private final String id;
    private final String name;
    private final int tier;
    private final String description;
    private final int unlockCost;
    private final Map<String, Object> effects;
}
```

- [ ] **Step 5: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/leaguesai/data/model/
git commit -m "feat: add data models for Task, Area, Relic, Difficulty"
```

---

## Task 3: SQLite DatabaseLoader

**Files:**
- Create: `src/main/java/com/leaguesai/data/DatabaseLoader.java`
- Create: `src/test/java/com/leaguesai/data/DatabaseLoaderTest.java`

- [ ] **Step 1: Write failing test for DatabaseLoader**

```java
package com.leaguesai.data;

import com.leaguesai.data.model.Task;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.Assert.*;

public class DatabaseLoaderTest
{
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File dbFile;

    @Before
    public void setUp() throws Exception
    {
        dbFile = new File(tempFolder.getRoot(), "test.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement stmt = conn.createStatement())
        {
            stmt.execute("CREATE TABLE tasks ("
                + "id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT, "
                + "difficulty TEXT, points INTEGER, area TEXT, category TEXT, "
                + "skills_required TEXT, quests_required TEXT, tasks_required TEXT, "
                + "items_required TEXT, location TEXT, target_npcs TEXT, "
                + "target_objects TEXT, target_items TEXT, wiki_url TEXT, "
                + "embedding BLOB)");
            stmt.execute("CREATE TABLE areas ("
                + "id TEXT PRIMARY KEY, name TEXT NOT NULL, unlock_cost INTEGER, "
                + "unlock_requires TEXT, region_ids TEXT)");
            stmt.execute("CREATE TABLE relics ("
                + "id TEXT PRIMARY KEY, name TEXT, tier INTEGER, "
                + "description TEXT, unlock_cost INTEGER, effects TEXT)");

            stmt.execute("INSERT INTO tasks (id, name, description, difficulty, points, area, category, "
                + "skills_required, location, target_npcs) VALUES ("
                + "'task-1', 'Catch a Shrimp', 'Catch a shrimp in Lumbridge', 'easy', 10, "
                + "'misthalin', 'skilling', '{\"fishing\": 1}', "
                + "'{\"x\": 3243, \"y\": 3152, \"plane\": 0}', "
                + "'[{\"id\": 1526, \"name\": \"Fishing spot\"}]')");

            stmt.execute("INSERT INTO areas (id, name, unlock_cost, unlock_requires, region_ids) "
                + "VALUES ('misthalin', 'Misthalin', 0, '[]', '[12850, 12851]')");
        }
    }

    @Test
    public void testLoadTasks()
    {
        DatabaseLoader loader = new DatabaseLoader(dbFile);
        List<Task> tasks = loader.loadTasks();

        assertEquals(1, tasks.size());
        Task task = tasks.get(0);
        assertEquals("task-1", task.getId());
        assertEquals("Catch a Shrimp", task.getName());
        assertEquals(Difficulty.EASY, task.getDifficulty());
        assertEquals(10, task.getPoints());
        assertEquals("misthalin", task.getArea());
        assertEquals(Integer.valueOf(1), task.getSkillsRequired().get("fishing"));
        assertNotNull(task.getLocation());
        assertEquals(3243, task.getLocation().getX());
    }

    @Test
    public void testLoadAreas()
    {
        DatabaseLoader loader = new DatabaseLoader(dbFile);
        List<Area> areas = loader.loadAreas();

        assertEquals(1, areas.size());
        Area area = areas.get(0);
        assertEquals("misthalin", area.getId());
        assertEquals(0, area.getUnlockCost());
        assertTrue(area.getRegionIds().contains(12850));
    }

    @Test
    public void testLoadEmbeddings()
    {
        DatabaseLoader loader = new DatabaseLoader(dbFile);
        // No embeddings stored yet, should return empty map
        var embeddings = loader.loadEmbeddings();
        assertTrue(embeddings.isEmpty());
    }

    @Test
    public void testMissingDbFile()
    {
        File missing = new File(tempFolder.getRoot(), "nope.db");
        DatabaseLoader loader = new DatabaseLoader(missing);
        List<Task> tasks = loader.loadTasks();
        assertTrue(tasks.isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.data.DatabaseLoaderTest`
Expected: FAIL — `DatabaseLoader` class does not exist

- [ ] **Step 3: Implement DatabaseLoader**

```java
package com.leaguesai.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.leaguesai.data.model.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;
import java.util.*;

@Slf4j
public class DatabaseLoader
{
    private final File dbFile;
    private final Gson gson = new Gson();

    public DatabaseLoader(File dbFile)
    {
        this.dbFile = dbFile;
    }

    private Connection connect()
    {
        try
        {
            return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        }
        catch (SQLException e)
        {
            log.error("Failed to connect to database: {}", dbFile, e);
            return null;
        }
    }

    public List<Task> loadTasks()
    {
        if (!dbFile.exists())
        {
            return Collections.emptyList();
        }

        List<Task> tasks = new ArrayList<>();
        try (Connection conn = connect())
        {
            if (conn == null) return Collections.emptyList();

            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM tasks");
            while (rs.next())
            {
                tasks.add(parseTask(rs));
            }
        }
        catch (SQLException e)
        {
            log.error("Failed to load tasks", e);
        }
        return tasks;
    }

    public List<Area> loadAreas()
    {
        if (!dbFile.exists())
        {
            return Collections.emptyList();
        }

        List<Area> areas = new ArrayList<>();
        try (Connection conn = connect())
        {
            if (conn == null) return Collections.emptyList();

            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM areas");
            while (rs.next())
            {
                areas.add(parseArea(rs));
            }
        }
        catch (SQLException e)
        {
            log.error("Failed to load areas", e);
        }
        return areas;
    }

    public List<Relic> loadRelics()
    {
        if (!dbFile.exists())
        {
            return Collections.emptyList();
        }

        List<Relic> relics = new ArrayList<>();
        try (Connection conn = connect())
        {
            if (conn == null) return Collections.emptyList();

            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM relics");
            while (rs.next())
            {
                relics.add(parseRelic(rs));
            }
        }
        catch (SQLException e)
        {
            log.error("Failed to load relics", e);
        }
        return relics;
    }

    public Map<String, float[]> loadEmbeddings()
    {
        if (!dbFile.exists())
        {
            return Collections.emptyMap();
        }

        Map<String, float[]> embeddings = new HashMap<>();
        try (Connection conn = connect())
        {
            if (conn == null) return Collections.emptyMap();

            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT id, embedding FROM tasks WHERE embedding IS NOT NULL");
            while (rs.next())
            {
                byte[] bytes = rs.getBytes("embedding");
                if (bytes != null && bytes.length > 0)
                {
                    float[] vec = bytesToFloats(bytes);
                    embeddings.put(rs.getString("id"), vec);
                }
            }
        }
        catch (SQLException e)
        {
            log.error("Failed to load embeddings", e);
        }
        return embeddings;
    }

    private Task parseTask(ResultSet rs) throws SQLException
    {
        Map<String, Integer> skillsReq = parseJsonMap(rs.getString("skills_required"));
        List<String> questsReq = parseJsonStringList(rs.getString("quests_required"));
        List<String> tasksReq = parseJsonStringList(rs.getString("tasks_required"));
        Map<String, Integer> itemsReq = parseJsonMap(rs.getString("items_required"));
        WorldPoint loc = parseWorldPoint(rs.getString("location"));
        List<Task.NpcTarget> npcs = parseNpcTargets(rs.getString("target_npcs"));
        List<Task.ObjectTarget> objects = parseObjectTargets(rs.getString("target_objects"));
        List<Task.ItemTarget> items = parseItemTargets(rs.getString("target_items"));

        String diffStr = rs.getString("difficulty");
        Difficulty diff = diffStr != null ? Difficulty.fromString(diffStr) : null;

        return Task.builder()
            .id(rs.getString("id"))
            .name(rs.getString("name"))
            .description(rs.getString("description"))
            .difficulty(diff)
            .points(rs.getInt("points"))
            .area(rs.getString("area"))
            .category(rs.getString("category"))
            .skillsRequired(skillsReq)
            .questsRequired(questsReq)
            .tasksRequired(tasksReq)
            .itemsRequired(itemsReq)
            .location(loc)
            .targetNpcs(npcs)
            .targetObjects(objects)
            .targetItems(items)
            .wikiUrl(rs.getString("wiki_url"))
            .build();
    }

    private Area parseArea(ResultSet rs) throws SQLException
    {
        return Area.builder()
            .id(rs.getString("id"))
            .name(rs.getString("name"))
            .unlockCost(rs.getInt("unlock_cost"))
            .unlockRequires(parseJsonStringList(rs.getString("unlock_requires")))
            .regionIds(parseJsonIntList(rs.getString("region_ids")))
            .build();
    }

    private Relic parseRelic(ResultSet rs) throws SQLException
    {
        return Relic.builder()
            .id(rs.getString("id"))
            .name(rs.getString("name"))
            .tier(rs.getInt("tier"))
            .description(rs.getString("description"))
            .unlockCost(rs.getInt("unlock_cost"))
            .effects(parseJsonObjectMap(rs.getString("effects")))
            .build();
    }

    private Map<String, Integer> parseJsonMap(String json)
    {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        return gson.fromJson(json, new TypeToken<Map<String, Integer>>(){}.getType());
    }

    private Map<String, Object> parseJsonObjectMap(String json)
    {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        return gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
    }

    private List<String> parseJsonStringList(String json)
    {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        return gson.fromJson(json, new TypeToken<List<String>>(){}.getType());
    }

    private List<Integer> parseJsonIntList(String json)
    {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        List<Double> doubles = gson.fromJson(json, new TypeToken<List<Double>>(){}.getType());
        List<Integer> ints = new ArrayList<>();
        for (Double d : doubles) ints.add(d.intValue());
        return ints;
    }

    private WorldPoint parseWorldPoint(String json)
    {
        if (json == null || json.isEmpty()) return null;
        Map<String, Double> map = gson.fromJson(json, new TypeToken<Map<String, Double>>(){}.getType());
        return new WorldPoint(
            map.getOrDefault("x", 0.0).intValue(),
            map.getOrDefault("y", 0.0).intValue(),
            map.getOrDefault("plane", 0.0).intValue()
        );
    }

    private List<Task.NpcTarget> parseNpcTargets(String json)
    {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> list = gson.fromJson(json,
            new TypeToken<List<Map<String, Object>>>(){}.getType());
        List<Task.NpcTarget> targets = new ArrayList<>();
        for (Map<String, Object> m : list)
        {
            targets.add(Task.NpcTarget.builder()
                .id(((Double) m.get("id")).intValue())
                .name((String) m.get("name"))
                .build());
        }
        return targets;
    }

    private List<Task.ObjectTarget> parseObjectTargets(String json)
    {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> list = gson.fromJson(json,
            new TypeToken<List<Map<String, Object>>>(){}.getType());
        List<Task.ObjectTarget> targets = new ArrayList<>();
        for (Map<String, Object> m : list)
        {
            targets.add(Task.ObjectTarget.builder()
                .id(((Double) m.get("id")).intValue())
                .name((String) m.get("name"))
                .build());
        }
        return targets;
    }

    private List<Task.ItemTarget> parseItemTargets(String json)
    {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> list = gson.fromJson(json,
            new TypeToken<List<Map<String, Object>>>(){}.getType());
        List<Task.ItemTarget> targets = new ArrayList<>();
        for (Map<String, Object> m : list)
        {
            targets.add(Task.ItemTarget.builder()
                .id(((Double) m.get("id")).intValue())
                .name((String) m.get("name"))
                .build());
        }
        return targets;
    }

    private float[] bytesToFloats(byte[] bytes)
    {
        FloatBuffer fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] floats = new float[fb.remaining()];
        fb.get(floats);
        return floats;
    }
}
```

(Add `import java.nio.FloatBuffer;` at the top.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests com.leaguesai.data.DatabaseLoaderTest`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leaguesai/data/DatabaseLoader.java src/test/java/com/leaguesai/data/DatabaseLoaderTest.java
git commit -m "feat: add DatabaseLoader for SQLite task/area/relic loading"
```

---

## Task 4: TaskRepository

**Files:**
- Create: `src/main/java/com/leaguesai/data/TaskRepository.java`
- Create: `src/main/java/com/leaguesai/data/TaskRepositoryImpl.java`
- Create: `src/test/java/com/leaguesai/data/TaskRepositoryImplTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class TaskRepositoryImplTest
{
    private TaskRepositoryImpl repo;

    @Before
    public void setUp()
    {
        Task shrimp = Task.builder()
            .id("task-1").name("Catch a Shrimp").difficulty(Difficulty.EASY)
            .points(10).area("misthalin").category("skilling")
            .skillsRequired(Map.of("fishing", 1))
            .tasksRequired(Collections.emptyList())
            .location(new WorldPoint(3243, 3152, 0))
            .targetNpcs(List.of(Task.NpcTarget.builder().id(1526).name("Fishing spot").build()))
            .build();

        Task cookShrimp = Task.builder()
            .id("task-2").name("Cook a Shrimp").difficulty(Difficulty.EASY)
            .points(10).area("misthalin").category("skilling")
            .skillsRequired(Map.of("cooking", 1))
            .tasksRequired(List.of("task-1"))
            .location(new WorldPoint(3231, 3196, 0))
            .build();

        Task lobster = Task.builder()
            .id("task-3").name("Catch a Lobster").difficulty(Difficulty.MEDIUM)
            .points(25).area("karamja").category("skilling")
            .skillsRequired(Map.of("fishing", 40))
            .tasksRequired(Collections.emptyList())
            .location(new WorldPoint(2924, 3178, 0))
            .build();

        Area misthalin = Area.builder()
            .id("misthalin").name("Misthalin").unlockCost(0)
            .unlockRequires(Collections.emptyList())
            .regionIds(List.of(12850, 12851))
            .build();

        repo = new TaskRepositoryImpl(List.of(shrimp, cookShrimp, lobster), List.of(misthalin));
    }

    @Test
    public void testGetById()
    {
        Task task = repo.getById("task-1");
        assertNotNull(task);
        assertEquals("Catch a Shrimp", task.getName());
    }

    @Test
    public void testGetByIdMissing()
    {
        assertNull(repo.getById("nonexistent"));
    }

    @Test
    public void testGetByArea()
    {
        List<Task> tasks = repo.getByArea("misthalin");
        assertEquals(2, tasks.size());
    }

    @Test
    public void testGetByDifficulty()
    {
        List<Task> tasks = repo.getByDifficulty(Difficulty.EASY);
        assertEquals(2, tasks.size());
    }

    @Test
    public void testGetPrerequisites()
    {
        List<Task> prereqs = repo.getPrerequisites("task-2");
        assertEquals(1, prereqs.size());
        assertEquals("task-1", prereqs.get(0).getId());
    }

    @Test
    public void testGetPrerequisitesRecursive()
    {
        // task-2 requires task-1. Getting all prereqs of task-2 should include task-1.
        List<Task> prereqs = repo.getAllPrerequisites("task-2");
        assertEquals(1, prereqs.size());
        assertEquals("task-1", prereqs.get(0).getId());
    }

    @Test
    public void testGetAllTasks()
    {
        assertEquals(3, repo.getAllTasks().size());
    }

    @Test
    public void testGetAreaByRegionId()
    {
        Area area = repo.getAreaByRegionId(12850);
        assertNotNull(area);
        assertEquals("misthalin", area.getId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.data.TaskRepositoryImplTest`
Expected: FAIL — classes don't exist

- [ ] **Step 3: Create TaskRepository interface**

```java
package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;

import java.util.List;

public interface TaskRepository
{
    List<Task> getAllTasks();
    Task getById(String id);
    List<Task> getByArea(String area);
    List<Task> getByDifficulty(Difficulty difficulty);
    List<Task> getPrerequisites(String taskId);
    List<Task> getAllPrerequisites(String taskId);
    List<Area> getAllAreas();
    Area getAreaByRegionId(int regionId);
}
```

- [ ] **Step 4: Create TaskRepositoryImpl**

```java
package com.leaguesai.data;

import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;

import java.util.*;
import java.util.stream.Collectors;

public class TaskRepositoryImpl implements TaskRepository
{
    private final Map<String, Task> tasksById;
    private final List<Task> allTasks;
    private final Map<String, Area> areasById;
    private final List<Area> allAreas;

    public TaskRepositoryImpl(List<Task> tasks, List<Area> areas)
    {
        this.allTasks = new ArrayList<>(tasks);
        this.tasksById = new HashMap<>();
        for (Task t : tasks)
        {
            tasksById.put(t.getId(), t);
        }
        this.allAreas = new ArrayList<>(areas);
        this.areasById = new HashMap<>();
        for (Area a : areas)
        {
            areasById.put(a.getId(), a);
        }
    }

    @Override
    public List<Task> getAllTasks()
    {
        return Collections.unmodifiableList(allTasks);
    }

    @Override
    public Task getById(String id)
    {
        return tasksById.get(id);
    }

    @Override
    public List<Task> getByArea(String area)
    {
        return allTasks.stream()
            .filter(t -> area.equals(t.getArea()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Task> getByDifficulty(Difficulty difficulty)
    {
        return allTasks.stream()
            .filter(t -> difficulty.equals(t.getDifficulty()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Task> getPrerequisites(String taskId)
    {
        Task task = tasksById.get(taskId);
        if (task == null || task.getTasksRequired() == null)
        {
            return Collections.emptyList();
        }
        return task.getTasksRequired().stream()
            .map(tasksById::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    @Override
    public List<Task> getAllPrerequisites(String taskId)
    {
        Set<String> visited = new LinkedHashSet<>();
        collectPrereqs(taskId, visited);
        return visited.stream()
            .map(tasksById::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    private void collectPrereqs(String taskId, Set<String> visited)
    {
        Task task = tasksById.get(taskId);
        if (task == null || task.getTasksRequired() == null)
        {
            return;
        }
        for (String prereqId : task.getTasksRequired())
        {
            if (!visited.contains(prereqId))
            {
                visited.add(prereqId);
                collectPrereqs(prereqId, visited);
            }
        }
    }

    @Override
    public List<Area> getAllAreas()
    {
        return Collections.unmodifiableList(allAreas);
    }

    @Override
    public Area getAreaByRegionId(int regionId)
    {
        return allAreas.stream()
            .filter(a -> a.getRegionIds() != null && a.getRegionIds().contains(regionId))
            .findFirst()
            .orElse(null);
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests com.leaguesai.data.TaskRepositoryImplTest`
Expected: All 8 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/leaguesai/data/TaskRepository.java src/main/java/com/leaguesai/data/TaskRepositoryImpl.java src/test/java/com/leaguesai/data/TaskRepositoryImplTest.java
git commit -m "feat: add TaskRepository with prerequisite resolution"
```

---

## Task 5: VectorIndex

**Files:**
- Create: `src/main/java/com/leaguesai/data/VectorIndex.java`
- Create: `src/test/java/com/leaguesai/data/VectorIndexTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.leaguesai.data;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class VectorIndexTest
{
    private VectorIndex index;

    @Before
    public void setUp()
    {
        // 3-dimensional vectors for simplicity
        index = new VectorIndex(Map.of(
            "task-1", new float[]{1.0f, 0.0f, 0.0f},
            "task-2", new float[]{0.0f, 1.0f, 0.0f},
            "task-3", new float[]{0.9f, 0.1f, 0.0f}
        ));
    }

    @Test
    public void testSearchSimilarReturnsClosestFirst()
    {
        float[] query = {1.0f, 0.0f, 0.0f};
        List<String> results = index.searchSimilar(query, 3);
        assertEquals("task-1", results.get(0));
        assertEquals("task-3", results.get(1));
        assertEquals("task-2", results.get(2));
    }

    @Test
    public void testSearchSimilarLimitResults()
    {
        float[] query = {1.0f, 0.0f, 0.0f};
        List<String> results = index.searchSimilar(query, 1);
        assertEquals(1, results.size());
        assertEquals("task-1", results.get(0));
    }

    @Test
    public void testEmptyIndex()
    {
        VectorIndex empty = new VectorIndex(Map.of());
        List<String> results = empty.searchSimilar(new float[]{1.0f}, 5);
        assertTrue(results.isEmpty());
    }

    @Test
    public void testCosineSimilarity()
    {
        float sim = VectorIndex.cosineSimilarity(
            new float[]{1.0f, 0.0f},
            new float[]{1.0f, 0.0f}
        );
        assertEquals(1.0f, sim, 0.001f);

        float ortho = VectorIndex.cosineSimilarity(
            new float[]{1.0f, 0.0f},
            new float[]{0.0f, 1.0f}
        );
        assertEquals(0.0f, ortho, 0.001f);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.data.VectorIndexTest`
Expected: FAIL

- [ ] **Step 3: Implement VectorIndex**

```java
package com.leaguesai.data;

import java.util.*;
import java.util.stream.Collectors;

public class VectorIndex
{
    private final Map<String, float[]> vectors;

    public VectorIndex(Map<String, float[]> vectors)
    {
        this.vectors = new HashMap<>(vectors);
    }

    public List<String> searchSimilar(float[] query, int limit)
    {
        if (vectors.isEmpty())
        {
            return Collections.emptyList();
        }

        return vectors.entrySet().stream()
            .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), cosineSimilarity(query, e.getValue())))
            .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .map(AbstractMap.SimpleEntry::getKey)
            .collect(Collectors.toList());
    }

    public static float cosineSimilarity(float[] a, float[] b)
    {
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++)
        {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denom == 0 ? 0f : dot / denom;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.leaguesai.data.VectorIndexTest`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leaguesai/data/VectorIndex.java src/test/java/com/leaguesai/data/VectorIndexTest.java
git commit -m "feat: add in-memory VectorIndex with cosine similarity search"
```

---

## Task 6: Core Events

**Files:**
- Create: `src/main/java/com/leaguesai/core/events/XpGainEvent.java`
- Create: `src/main/java/com/leaguesai/core/events/TaskCompletedEvent.java`
- Create: `src/main/java/com/leaguesai/core/events/InventoryStateEvent.java`
- Create: `src/main/java/com/leaguesai/core/events/SkillStateEvent.java`
- Create: `src/main/java/com/leaguesai/core/events/LocationChangedEvent.java`
- Create: `src/main/java/com/leaguesai/core/events/AreaUnlockedEvent.java`
- Create: `src/main/java/com/leaguesai/core/events/PlanUpdatedEvent.java`

- [ ] **Step 1: Create all event classes**

```java
// XpGainEvent.java
package com.leaguesai.core.events;

import lombok.Value;
import net.runelite.api.Skill;

@Value
public class XpGainEvent
{
    Skill skill;
    int delta;
    int totalXp;
}
```

```java
// TaskCompletedEvent.java
package com.leaguesai.core.events;

import lombok.Value;

@Value
public class TaskCompletedEvent
{
    String taskId;
    String taskName;
}
```

```java
// InventoryStateEvent.java
package com.leaguesai.core.events;

import lombok.Value;
import java.util.Map;

@Value
public class InventoryStateEvent
{
    Map<Integer, Integer> items; // itemId -> quantity
}
```

```java
// SkillStateEvent.java
package com.leaguesai.core.events;

import lombok.Value;
import net.runelite.api.Skill;
import java.util.Map;

@Value
public class SkillStateEvent
{
    Map<Skill, Integer> levels;
    Map<Skill, Integer> boostedLevels;
}
```

```java
// LocationChangedEvent.java
package com.leaguesai.core.events;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
public class LocationChangedEvent
{
    WorldPoint worldPoint;
    int regionId;
}
```

```java
// AreaUnlockedEvent.java
package com.leaguesai.core.events;

import lombok.Value;

@Value
public class AreaUnlockedEvent
{
    String areaName;
}
```

```java
// PlanUpdatedEvent.java
package com.leaguesai.core.events;

import com.leaguesai.agent.PlannedStep;
import lombok.Value;
import java.util.List;

@Value
public class PlanUpdatedEvent
{
    List<PlannedStep> steps;
    int currentStepIndex;
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/leaguesai/core/events/
git commit -m "feat: add custom event classes for inter-module communication"
```

---

## Task 7: Core Monitors

**Files:**
- Create: `src/main/java/com/leaguesai/core/monitors/XpMonitor.java`
- Create: `src/main/java/com/leaguesai/core/monitors/InventoryMonitor.java`
- Create: `src/main/java/com/leaguesai/core/monitors/LocationMonitor.java`
- Create: `src/test/java/com/leaguesai/core/monitors/XpMonitorTest.java`
- Create: `src/test/java/com/leaguesai/core/monitors/LocationMonitorTest.java`

- [ ] **Step 1: Write failing test for XpMonitor**

```java
package com.leaguesai.core.monitors;

import com.leaguesai.core.events.XpGainEvent;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class XpMonitorTest
{
    @Mock
    private Client client;

    @Mock
    private EventBus eventBus;

    private XpMonitor monitor;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        monitor = new XpMonitor(client, eventBus);
    }

    @Test
    public void testDetectsXpGain()
    {
        // Initialize with 0 XP
        when(client.getSkillExperience(Skill.FISHING)).thenReturn(0);
        monitor.initialize();

        // Simulate XP gain
        when(client.getSkillExperience(Skill.FISHING)).thenReturn(100);
        StatChanged event = mock(StatChanged.class);
        when(event.getSkill()).thenReturn(Skill.FISHING);
        when(event.getXp()).thenReturn(100);

        monitor.onStatChanged(event);

        ArgumentCaptor<XpGainEvent> captor = ArgumentCaptor.forClass(XpGainEvent.class);
        verify(eventBus).post(captor.capture());
        XpGainEvent xpEvent = captor.getValue();
        assertEquals(Skill.FISHING, xpEvent.getSkill());
        assertEquals(100, xpEvent.getDelta());
        assertEquals(100, xpEvent.getTotalXp());
    }

    @Test
    public void testNoEventWhenNoXpChange()
    {
        when(client.getSkillExperience(Skill.FISHING)).thenReturn(100);
        monitor.initialize();

        StatChanged event = mock(StatChanged.class);
        when(event.getSkill()).thenReturn(Skill.FISHING);
        when(event.getXp()).thenReturn(100);

        monitor.onStatChanged(event);

        verify(eventBus, never()).post(any(XpGainEvent.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.core.monitors.XpMonitorTest`
Expected: FAIL

- [ ] **Step 3: Implement XpMonitor**

```java
package com.leaguesai.core.monitors;

import com.leaguesai.core.events.XpGainEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.EnumMap;
import java.util.Map;

@Slf4j
@Singleton
public class XpMonitor
{
    private final Client client;
    private final EventBus eventBus;
    private final Map<Skill, Integer> previousXp = new EnumMap<>(Skill.class);

    @Inject
    public XpMonitor(Client client, EventBus eventBus)
    {
        this.client = client;
        this.eventBus = eventBus;
    }

    public void initialize()
    {
        for (Skill skill : Skill.values())
        {
            if (skill == Skill.OVERALL) continue;
            previousXp.put(skill, client.getSkillExperience(skill));
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        Skill skill = event.getSkill();
        int currentXp = event.getXp();
        int prevXp = previousXp.getOrDefault(skill, 0);

        if (currentXp > prevXp)
        {
            int delta = currentXp - prevXp;
            previousXp.put(skill, currentXp);
            eventBus.post(new XpGainEvent(skill, delta, currentXp));
            log.debug("XP gain: {} +{} (total: {})", skill, delta, currentXp);
        }
        else
        {
            previousXp.put(skill, currentXp);
        }
    }
}
```

- [ ] **Step 4: Run XpMonitor test**

Run: `./gradlew test --tests com.leaguesai.core.monitors.XpMonitorTest`
Expected: PASS

- [ ] **Step 5: Implement InventoryMonitor**

```java
package com.leaguesai.core.monitors;

import com.leaguesai.core.events.InventoryStateEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Singleton
public class InventoryMonitor
{
    private final EventBus eventBus;

    @Inject
    public InventoryMonitor(EventBus eventBus)
    {
        this.eventBus = eventBus;
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() != InventoryID.INVENTORY.getId())
        {
            return;
        }

        ItemContainer container = event.getItemContainer();
        Map<Integer, Integer> items = new HashMap<>();
        for (Item item : container.getItems())
        {
            if (item.getId() != -1)
            {
                items.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
        }

        eventBus.post(new InventoryStateEvent(items));
    }
}
```

- [ ] **Step 6: Implement LocationMonitor**

```java
package com.leaguesai.core.monitors;

import com.leaguesai.core.events.LocationChangedEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;

@Slf4j
@Singleton
public class LocationMonitor
{
    private final Client client;
    private final EventBus eventBus;
    private int lastRegionId = -1;

    @Inject
    public LocationMonitor(Client client, EventBus eventBus)
    {
        this.client = client;
        this.eventBus = eventBus;
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        Player player = client.getLocalPlayer();
        if (player == null) return;

        WorldPoint pos = player.getWorldLocation();
        int regionId = pos.getRegionID();

        if (regionId != lastRegionId)
        {
            lastRegionId = regionId;
            eventBus.post(new LocationChangedEvent(pos, regionId));
        }
    }

    public WorldPoint getCurrentLocation()
    {
        Player player = client.getLocalPlayer();
        return player != null ? player.getWorldLocation() : null;
    }
}
```

- [ ] **Step 7: Write LocationMonitor test**

```java
package com.leaguesai.core.monitors;

import com.leaguesai.core.events.LocationChangedEvent;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LocationMonitorTest
{
    @Mock private Client client;
    @Mock private EventBus eventBus;
    @Mock private Player player;

    private LocationMonitor monitor;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        monitor = new LocationMonitor(client, eventBus);
        when(client.getLocalPlayer()).thenReturn(player);
    }

    @Test
    public void testFiresEventOnRegionChange()
    {
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));

        monitor.onGameTick(new GameTick());

        ArgumentCaptor<LocationChangedEvent> captor = ArgumentCaptor.forClass(LocationChangedEvent.class);
        verify(eventBus).post(captor.capture());
        assertEquals(3200, captor.getValue().getWorldPoint().getX());
    }

    @Test
    public void testNoEventWhenSameRegion()
    {
        WorldPoint pos = new WorldPoint(3200, 3200, 0);
        when(player.getWorldLocation()).thenReturn(pos);

        monitor.onGameTick(new GameTick());
        monitor.onGameTick(new GameTick());

        // Only one event, not two
        verify(eventBus, times(1)).post(any(LocationChangedEvent.class));
    }
}
```

- [ ] **Step 8: Run all monitor tests**

Run: `./gradlew test --tests "com.leaguesai.core.monitors.*"`
Expected: All PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/leaguesai/core/monitors/ src/test/java/com/leaguesai/core/monitors/
git commit -m "feat: add XP, inventory, and location monitors"
```

---

## Task 8: PlannedStep and OverlayData

**Files:**
- Create: `src/main/java/com/leaguesai/agent/PlannedStep.java`
- Create: `src/main/java/com/leaguesai/overlay/OverlayData.java`
- Create: `src/main/java/com/leaguesai/ui/AnimationType.java`

- [ ] **Step 1: Create AnimationType enum**

```java
package com.leaguesai.ui;

public enum AnimationType
{
    IDLE,
    WALKING,
    BANKING,
    MELEE,
    RANGED,
    MAGE,
    COOKING,
    FISHING,
    WOODCUTTING,
    MINING
}
```

- [ ] **Step 2: Create OverlayData**

```java
package com.leaguesai.overlay;

import lombok.Builder;
import lombok.Data;
import net.runelite.api.coords.WorldPoint;
import java.util.List;

@Data
@Builder
public class OverlayData
{
    private final WorldPoint targetTile;
    private final List<Integer> targetNpcIds;
    private final List<Integer> targetObjectIds;
    private final List<Integer> targetItemIds;
    private final List<WorldPoint> pathPoints;
    private final List<Integer> widgetIds;
    private final boolean showArrow;
    private final boolean showMinimap;
    private final boolean showWorldMap;
}
```

- [ ] **Step 3: Create PlannedStep**

```java
package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import com.leaguesai.overlay.OverlayData;
import com.leaguesai.ui.AnimationType;
import lombok.Builder;
import lombok.Data;
import net.runelite.api.coords.WorldPoint;
import java.util.List;

@Data
@Builder
public class PlannedStep
{
    private final Task task;
    private final WorldPoint destination;
    private final List<Integer> requiredItems;
    private final String instruction;
    private final OverlayData overlayData;
    private final AnimationType animation;
}
```

- [ ] **Step 4: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leaguesai/agent/PlannedStep.java src/main/java/com/leaguesai/overlay/OverlayData.java src/main/java/com/leaguesai/ui/AnimationType.java
git commit -m "feat: add PlannedStep, OverlayData, and AnimationType"
```

---

## Task 9: OpenAI Client

**Files:**
- Create: `src/main/java/com/leaguesai/agent/OpenAiClient.java`
- Create: `src/test/java/com/leaguesai/agent/OpenAiClientTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.leaguesai.agent;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class OpenAiClientTest
{
    private MockWebServer server;
    private OpenAiClient client;

    @Before
    public void setUp() throws Exception
    {
        server = new MockWebServer();
        server.start();
        client = new OpenAiClient("test-key", "gpt-4o", server.url("/").toString());
    }

    @After
    public void tearDown() throws Exception
    {
        server.shutdown();
    }

    @Test
    public void testChatCompletion() throws Exception
    {
        server.enqueue(new MockResponse()
            .setBody("{\"choices\":[{\"message\":{\"content\":\"Hello there\"}}]}")
            .setHeader("Content-Type", "application/json"));

        String response = client.chatCompletion("system prompt", List.of(
            new OpenAiClient.Message("user", "hi")
        ));

        assertEquals("Hello there", response);

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getHeader("Authorization").contains("test-key"));
    }

    @Test
    public void testGetEmbedding() throws Exception
    {
        server.enqueue(new MockResponse()
            .setBody("{\"data\":[{\"embedding\":[0.1, 0.2, 0.3]}]}")
            .setHeader("Content-Type", "application/json"));

        float[] embedding = client.getEmbedding("test text");

        assertEquals(3, embedding.length);
        assertEquals(0.1f, embedding[0], 0.01f);
    }
}
```

Add to build.gradle dependencies:
```gradle
testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.agent.OpenAiClientTest`
Expected: FAIL

- [ ] **Step 3: Implement OpenAiClient**

```java
package com.leaguesai.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class OpenAiClient
{
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    public OpenAiClient(String apiKey, String model)
    {
        this(apiKey, model, DEFAULT_BASE_URL);
    }

    public OpenAiClient(String apiKey, String model, String baseUrl)
    {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    }

    public String chatCompletion(String systemPrompt, List<Message> messages) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray msgs = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        msgs.add(sysMsg);

        for (Message m : messages)
        {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", m.getRole());
            msg.addProperty("content", m.getContent());
            msgs.add(msg);
        }
        body.add("messages", msgs);

        Request request = new Request.Builder()
            .url(baseUrl + "chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .post(RequestBody.create(gson.toJson(body), JSON))
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            String responseBody = response.body().string();
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            return json.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
        }
    }

    public float[] getEmbedding(String text) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("model", "text-embedding-3-small");
        body.addProperty("input", text);

        Request request = new Request.Builder()
            .url(baseUrl + "embeddings")
            .header("Authorization", "Bearer " + apiKey)
            .post(RequestBody.create(gson.toJson(body), JSON))
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            String responseBody = response.body().string();
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            JsonArray arr = json.getAsJsonArray("data")
                .get(0).getAsJsonObject()
                .getAsJsonArray("embedding");
            float[] embedding = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++)
            {
                embedding[i] = arr.get(i).getAsFloat();
            }
            return embedding;
        }
    }

    @Data
    @AllArgsConstructor
    public static class Message
    {
        private String role;
        private String content;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.leaguesai.agent.OpenAiClientTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add build.gradle src/main/java/com/leaguesai/agent/OpenAiClient.java src/test/java/com/leaguesai/agent/OpenAiClientTest.java
git commit -m "feat: add OpenAI client with chat completion and embedding support"
```

---

## Task 10: PlayerContext and PromptBuilder

**Files:**
- Create: `src/main/java/com/leaguesai/agent/PlayerContext.java`
- Create: `src/main/java/com/leaguesai/agent/PlayerContextAssembler.java`
- Create: `src/main/java/com/leaguesai/agent/PromptBuilder.java`
- Create: `src/test/java/com/leaguesai/agent/PromptBuilderTest.java`

- [ ] **Step 1: Create PlayerContext**

```java
package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import lombok.Builder;
import lombok.Data;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
public class PlayerContext
{
    private final Map<Skill, Integer> levels;
    private final Map<Skill, Integer> xp;
    private final Map<Integer, Integer> inventory;
    private final Map<Integer, Integer> equipment;
    private final Set<String> completedTasks;
    private final Set<String> unlockedAreas;
    private final WorldPoint location;
    private final int leaguePoints;
    private final int combatLevel;
    private final String currentGoal;
    private final List<PlannedStep> currentPlan;
}
```

- [ ] **Step 2: Create PlayerContextAssembler**

```java
package com.leaguesai.agent;

import com.leaguesai.core.monitors.LocationMonitor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

@Slf4j
@Singleton
public class PlayerContextAssembler
{
    private final Client client;
    private final LocationMonitor locationMonitor;

    private Set<String> completedTasks = new HashSet<>();
    private Set<String> unlockedAreas = new HashSet<>();
    private String currentGoal = "";
    private List<PlannedStep> currentPlan = new ArrayList<>();

    @Inject
    public PlayerContextAssembler(Client client, LocationMonitor locationMonitor)
    {
        this.client = client;
        this.locationMonitor = locationMonitor;
    }

    public PlayerContext assemble()
    {
        return PlayerContext.builder()
            .levels(getSkillLevels())
            .xp(getSkillXp())
            .inventory(getInventory())
            .equipment(getEquipment())
            .completedTasks(completedTasks)
            .unlockedAreas(unlockedAreas)
            .location(locationMonitor.getCurrentLocation())
            .leaguePoints(0) // TODO: read from varbit when known
            .combatLevel(client.getLocalPlayer() != null ? client.getLocalPlayer().getCombatLevel() : 0)
            .currentGoal(currentGoal)
            .currentPlan(currentPlan)
            .build();
    }

    private Map<Skill, Integer> getSkillLevels()
    {
        Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
        for (Skill skill : Skill.values())
        {
            if (skill == Skill.OVERALL) continue;
            levels.put(skill, client.getRealSkillLevel(skill));
        }
        return levels;
    }

    private Map<Skill, Integer> getSkillXp()
    {
        Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
        for (Skill skill : Skill.values())
        {
            if (skill == Skill.OVERALL) continue;
            xp.put(skill, client.getSkillExperience(skill));
        }
        return xp;
    }

    private Map<Integer, Integer> getInventory()
    {
        Map<Integer, Integer> items = new HashMap<>();
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv != null)
        {
            for (Item item : inv.getItems())
            {
                if (item.getId() != -1)
                {
                    items.merge(item.getId(), item.getQuantity(), Integer::sum);
                }
            }
        }
        return items;
    }

    private Map<Integer, Integer> getEquipment()
    {
        Map<Integer, Integer> items = new HashMap<>();
        ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equip != null)
        {
            for (Item item : equip.getItems())
            {
                if (item.getId() != -1)
                {
                    items.put(item.getId(), item.getQuantity());
                }
            }
        }
        return items;
    }

    public void markTaskCompleted(String taskId) { completedTasks.add(taskId); }
    public void markAreaUnlocked(String area) { unlockedAreas.add(area); }
    public void setCurrentGoal(String goal) { this.currentGoal = goal; }
    public void setCurrentPlan(List<PlannedStep> plan) { this.currentPlan = plan; }
}
```

- [ ] **Step 3: Write failing PromptBuilder test**

```java
package com.leaguesai.agent;

import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class PromptBuilderTest
{
    @Test
    public void testBuildSystemPrompt()
    {
        PlayerContext ctx = PlayerContext.builder()
            .levels(Map.of(Skill.FISHING, 50, Skill.COOKING, 45))
            .xp(Map.of(Skill.FISHING, 101333, Skill.COOKING, 61512))
            .inventory(Map.of(311, 5)) // 5 trout
            .equipment(Map.of())
            .completedTasks(Set.of("task-1"))
            .unlockedAreas(Set.of("misthalin"))
            .location(new WorldPoint(3200, 3200, 0))
            .leaguePoints(150)
            .combatLevel(30)
            .currentGoal("Complete all easy tasks in Misthalin")
            .currentPlan(Collections.emptyList())
            .build();

        String prompt = PromptBuilder.buildSystemPrompt(ctx);

        assertTrue(prompt.contains("FISHING: 50"));
        assertTrue(prompt.contains("COOKING: 45"));
        assertTrue(prompt.contains("misthalin"));
        assertTrue(prompt.contains("150"));
        assertTrue(prompt.contains("Complete all easy tasks in Misthalin"));
    }

    @Test
    public void testBuildPlanningPrompt()
    {
        List<Task> tasks = List.of(
            Task.builder().id("t1").name("Catch Shrimp").difficulty(Difficulty.EASY)
                .points(10).area("misthalin").build()
        );

        String prompt = PromptBuilder.buildPlanningPrompt("unlock karamja", tasks);

        assertTrue(prompt.contains("unlock karamja"));
        assertTrue(prompt.contains("Catch Shrimp"));
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.agent.PromptBuilderTest`
Expected: FAIL

- [ ] **Step 5: Implement PromptBuilder**

```java
package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import net.runelite.api.Skill;

import java.util.List;
import java.util.Map;

public class PromptBuilder
{
    public static String buildSystemPrompt(PlayerContext ctx)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert OSRS Leagues VI (Demonic Pacts) coach. ");
        sb.append("Help the player complete league tasks efficiently.\n\n");

        sb.append("## Player State\n");
        sb.append("Combat Level: ").append(ctx.getCombatLevel()).append("\n");
        sb.append("League Points: ").append(ctx.getLeaguePoints()).append("\n");
        if (ctx.getLocation() != null)
        {
            sb.append("Location: (").append(ctx.getLocation().getX())
                .append(", ").append(ctx.getLocation().getY()).append(")\n");
        }

        sb.append("\n## Skills\n");
        if (ctx.getLevels() != null)
        {
            for (Map.Entry<Skill, Integer> entry : ctx.getLevels().entrySet())
            {
                sb.append(entry.getKey().name()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        sb.append("\n## Unlocked Areas\n");
        if (ctx.getUnlockedAreas() != null)
        {
            for (String area : ctx.getUnlockedAreas())
            {
                sb.append("- ").append(area).append("\n");
            }
        }

        sb.append("\n## Completed Tasks: ").append(
            ctx.getCompletedTasks() != null ? ctx.getCompletedTasks().size() : 0
        ).append("\n");

        if (ctx.getCurrentGoal() != null && !ctx.getCurrentGoal().isEmpty())
        {
            sb.append("\n## Current Goal\n").append(ctx.getCurrentGoal()).append("\n");
        }

        if (ctx.getCurrentPlan() != null && !ctx.getCurrentPlan().isEmpty())
        {
            sb.append("\n## Current Plan (next 5 steps)\n");
            int limit = Math.min(5, ctx.getCurrentPlan().size());
            for (int i = 0; i < limit; i++)
            {
                PlannedStep step = ctx.getCurrentPlan().get(i);
                sb.append(i + 1).append(". ").append(step.getInstruction()).append("\n");
            }
        }

        return sb.toString();
    }

    public static String buildPlanningPrompt(String goal, List<Task> candidateTasks)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Plan a task sequence to achieve this goal: ").append(goal).append("\n\n");
        sb.append("Available tasks:\n");
        for (Task t : candidateTasks)
        {
            sb.append("- ").append(t.getName())
                .append(" [").append(t.getDifficulty()).append(", ")
                .append(t.getPoints()).append("pts, ")
                .append(t.getArea()).append("]");
            if (t.getSkillsRequired() != null && !t.getSkillsRequired().isEmpty())
            {
                sb.append(" requires: ").append(t.getSkillsRequired());
            }
            sb.append("\n");
        }
        sb.append("\nOptimize for: proximity (group by area), difficulty tier (easy first), ");
        sb.append("skill batching (consecutive same-skill tasks). ");
        sb.append("Return a JSON array of task IDs in recommended order.");
        return sb.toString();
    }

    public static String buildAdvicePrompt()
    {
        return "Given the player's current state, goal, and plan progress, "
            + "what should they focus on next and why? Be concise (2-3 paragraphs). "
            + "Consider: efficiency, upcoming unlocks, skill milestones, and any "
            + "opportunities to batch nearby tasks.";
    }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew test --tests com.leaguesai.agent.PromptBuilderTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/leaguesai/agent/PlayerContext.java src/main/java/com/leaguesai/agent/PlayerContextAssembler.java src/main/java/com/leaguesai/agent/PromptBuilder.java src/test/java/com/leaguesai/agent/PromptBuilderTest.java
git commit -m "feat: add PlayerContext, assembler, and PromptBuilder"
```

---

## Task 11: GoalPlanner — DAG + Topological Sort

**Files:**
- Create: `src/main/java/com/leaguesai/agent/GoalPlanner.java`
- Create: `src/main/java/com/leaguesai/agent/PlannerOptimizer.java`
- Create: `src/test/java/com/leaguesai/agent/GoalPlannerTest.java`
- Create: `src/test/java/com/leaguesai/agent/PlannerOptimizerTest.java`

- [ ] **Step 1: Write failing GoalPlanner test**

```java
package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GoalPlannerTest
{
    @Mock private TaskRepository taskRepo;

    private GoalPlanner planner;

    private Task taskA, taskB, taskC, taskD;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);

        taskA = Task.builder().id("a").name("Task A").difficulty(Difficulty.EASY)
            .points(10).area("misthalin").category("skilling")
            .tasksRequired(Collections.emptyList())
            .location(new WorldPoint(3200, 3200, 0))
            .build();

        taskB = Task.builder().id("b").name("Task B").difficulty(Difficulty.EASY)
            .points(10).area("misthalin").category("skilling")
            .tasksRequired(List.of("a"))
            .location(new WorldPoint(3210, 3200, 0))
            .build();

        taskC = Task.builder().id("c").name("Task C").difficulty(Difficulty.MEDIUM)
            .points(25).area("misthalin").category("combat")
            .tasksRequired(List.of("a"))
            .location(new WorldPoint(3220, 3200, 0))
            .build();

        taskD = Task.builder().id("d").name("Task D").difficulty(Difficulty.MEDIUM)
            .points(25).area("karamja").category("skilling")
            .tasksRequired(Collections.emptyList())
            .location(new WorldPoint(2900, 3100, 0))
            .build();

        when(taskRepo.getById("a")).thenReturn(taskA);
        when(taskRepo.getById("b")).thenReturn(taskB);
        when(taskRepo.getById("c")).thenReturn(taskC);
        when(taskRepo.getById("d")).thenReturn(taskD);
        when(taskRepo.getAllTasks()).thenReturn(List.of(taskA, taskB, taskC, taskD));
        when(taskRepo.getByArea("misthalin")).thenReturn(List.of(taskA, taskB, taskC));
        when(taskRepo.getPrerequisites("b")).thenReturn(List.of(taskA));
        when(taskRepo.getPrerequisites("c")).thenReturn(List.of(taskA));
        when(taskRepo.getPrerequisites("a")).thenReturn(Collections.emptyList());
        when(taskRepo.getPrerequisites("d")).thenReturn(Collections.emptyList());
        when(taskRepo.getAllPrerequisites("b")).thenReturn(List.of(taskA));
        when(taskRepo.getAllPrerequisites("c")).thenReturn(List.of(taskA));
        when(taskRepo.getAllPrerequisites("a")).thenReturn(Collections.emptyList());
        when(taskRepo.getAllPrerequisites("d")).thenReturn(Collections.emptyList());

        planner = new GoalPlanner(taskRepo);
    }

    @Test
    public void testBuildDagResolvesPrereqs()
    {
        List<Task> targets = List.of(taskB, taskC);
        Set<String> completed = Set.of();

        List<Task> dag = planner.buildDag(targets, completed);

        // Should include taskA (prereq) plus taskB and taskC
        assertEquals(3, dag.size());
        Set<String> ids = new HashSet<>();
        for (Task t : dag) ids.add(t.getId());
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
        assertTrue(ids.contains("c"));
    }

    @Test
    public void testBuildDagPrunesCompleted()
    {
        List<Task> targets = List.of(taskB);
        Set<String> completed = Set.of("a");

        List<Task> dag = planner.buildDag(targets, completed);

        // taskA is completed, so only taskB
        assertEquals(1, dag.size());
        assertEquals("b", dag.get(0).getId());
    }

    @Test
    public void testTopologicalSort()
    {
        List<Task> dag = List.of(taskB, taskA, taskC);

        List<Task> sorted = planner.topologicalSort(dag);

        // A must come before B and C
        int idxA = -1, idxB = -1, idxC = -1;
        for (int i = 0; i < sorted.size(); i++)
        {
            if (sorted.get(i).getId().equals("a")) idxA = i;
            if (sorted.get(i).getId().equals("b")) idxB = i;
            if (sorted.get(i).getId().equals("c")) idxC = i;
        }
        assertTrue(idxA < idxB);
        assertTrue(idxA < idxC);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.agent.GoalPlannerTest`
Expected: FAIL

- [ ] **Step 3: Implement GoalPlanner**

```java
package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Task;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

@Slf4j
@Singleton
public class GoalPlanner
{
    private final TaskRepository taskRepo;

    @Inject
    public GoalPlanner(TaskRepository taskRepo)
    {
        this.taskRepo = taskRepo;
    }

    public List<Task> buildDag(List<Task> targetTasks, Set<String> completedTaskIds)
    {
        Map<String, Task> dag = new LinkedHashMap<>();
        Deque<Task> queue = new ArrayDeque<>(targetTasks);

        while (!queue.isEmpty())
        {
            Task task = queue.poll();
            if (completedTaskIds.contains(task.getId()) || dag.containsKey(task.getId()))
            {
                continue;
            }
            dag.put(task.getId(), task);

            List<Task> prereqs = taskRepo.getPrerequisites(task.getId());
            for (Task prereq : prereqs)
            {
                if (!completedTaskIds.contains(prereq.getId()) && !dag.containsKey(prereq.getId()))
                {
                    queue.add(prereq);
                }
            }
        }

        return new ArrayList<>(dag.values());
    }

    public List<Task> topologicalSort(List<Task> tasks)
    {
        Map<String, Task> taskMap = new LinkedHashMap<>();
        for (Task t : tasks) taskMap.put(t.getId(), t);

        Map<String, Set<String>> inEdges = new HashMap<>();
        for (Task t : tasks)
        {
            inEdges.put(t.getId(), new HashSet<>());
        }
        for (Task t : tasks)
        {
            if (t.getTasksRequired() != null)
            {
                for (String prereqId : t.getTasksRequired())
                {
                    if (taskMap.containsKey(prereqId))
                    {
                        inEdges.get(t.getId()).add(prereqId);
                    }
                }
            }
        }

        List<Task> sorted = new ArrayList<>();
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Set<String>> e : inEdges.entrySet())
        {
            if (e.getValue().isEmpty()) ready.add(e.getKey());
        }

        while (!ready.isEmpty())
        {
            String id = ready.poll();
            sorted.add(taskMap.get(id));

            for (Map.Entry<String, Set<String>> e : inEdges.entrySet())
            {
                if (e.getValue().remove(id) && e.getValue().isEmpty())
                {
                    ready.add(e.getKey());
                }
            }
        }

        return sorted;
    }

    public List<Task> resolveGoalTasks(String goal)
    {
        // Simple keyword matching for now. LLM disambiguation happens in the agent layer.
        String lowerGoal = goal.toLowerCase();
        List<Task> allTasks = taskRepo.getAllTasks();
        List<Task> matches = new ArrayList<>();

        for (Task t : allTasks)
        {
            if (lowerGoal.contains(t.getArea() != null ? t.getArea().toLowerCase() : ""))
            {
                if (lowerGoal.contains("easy") && t.getDifficulty() != null
                    && t.getDifficulty().name().equalsIgnoreCase("easy"))
                {
                    matches.add(t);
                }
                else if (lowerGoal.contains("medium") && t.getDifficulty() != null
                    && t.getDifficulty().name().equalsIgnoreCase("medium"))
                {
                    matches.add(t);
                }
                else if (!lowerGoal.contains("easy") && !lowerGoal.contains("medium")
                    && !lowerGoal.contains("hard") && !lowerGoal.contains("elite")
                    && !lowerGoal.contains("master"))
                {
                    matches.add(t);
                }
            }
        }

        return matches;
    }
}
```

- [ ] **Step 4: Run GoalPlanner tests**

Run: `./gradlew test --tests com.leaguesai.agent.GoalPlannerTest`
Expected: PASS

- [ ] **Step 5: Write failing PlannerOptimizer test**

```java
package com.leaguesai.agent;

import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class PlannerOptimizerTest
{
    @Test
    public void testGroupsByArea()
    {
        Task t1 = Task.builder().id("1").name("T1").area("misthalin")
            .difficulty(Difficulty.EASY).points(10)
            .tasksRequired(Collections.emptyList())
            .location(new WorldPoint(3200, 3200, 0)).build();
        Task t2 = Task.builder().id("2").name("T2").area("karamja")
            .difficulty(Difficulty.EASY).points(10)
            .tasksRequired(Collections.emptyList())
            .location(new WorldPoint(2900, 3100, 0)).build();
        Task t3 = Task.builder().id("3").name("T3").area("misthalin")
            .difficulty(Difficulty.EASY).points(10)
            .tasksRequired(Collections.emptyList())
            .location(new WorldPoint(3205, 3200, 0)).build();

        List<Task> optimized = PlannerOptimizer.optimizeOrder(
            List.of(t1, t2, t3), new WorldPoint(3200, 3200, 0));

        // Misthalin tasks should be grouped together
        String area0 = optimized.get(0).getArea();
        String area1 = optimized.get(1).getArea();
        assertEquals(area0, area1); // first two should be same area
    }

    @Test
    public void testSortsByDifficultyWithinArea()
    {
        Task hard = Task.builder().id("1").name("Hard").area("misthalin")
            .difficulty(Difficulty.HARD).points(50)
            .tasksRequired(Collections.emptyList())
            .location(new WorldPoint(3200, 3200, 0)).build();
        Task easy = Task.builder().id("2").name("Easy").area("misthalin")
            .difficulty(Difficulty.EASY).points(10)
            .tasksRequired(Collections.emptyList())
            .location(new WorldPoint(3200, 3200, 0)).build();

        List<Task> optimized = PlannerOptimizer.optimizeOrder(
            List.of(hard, easy), new WorldPoint(3200, 3200, 0));

        assertEquals(Difficulty.EASY, optimized.get(0).getDifficulty());
        assertEquals(Difficulty.HARD, optimized.get(1).getDifficulty());
    }
}
```

- [ ] **Step 6: Run PlannerOptimizer test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.agent.PlannerOptimizerTest`
Expected: FAIL

- [ ] **Step 7: Implement PlannerOptimizer**

```java
package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import net.runelite.api.coords.WorldPoint;

import java.util.*;
import java.util.stream.Collectors;

public class PlannerOptimizer
{
    public static List<Task> optimizeOrder(List<Task> tasks, WorldPoint playerLocation)
    {
        // Group by area
        Map<String, List<Task>> byArea = new LinkedHashMap<>();
        for (Task t : tasks)
        {
            byArea.computeIfAbsent(t.getArea() != null ? t.getArea() : "unknown",
                k -> new ArrayList<>()).add(t);
        }

        // Sort each area group by difficulty tier (easy first)
        for (List<Task> group : byArea.values())
        {
            group.sort(Comparator.comparingInt(t ->
                t.getDifficulty() != null ? t.getDifficulty().getTier() : 99));
        }

        // Sort area groups by distance from player
        List<Map.Entry<String, List<Task>>> areaEntries = new ArrayList<>(byArea.entrySet());
        areaEntries.sort(Comparator.comparingInt(e -> {
            WorldPoint firstLoc = e.getValue().get(0).getLocation();
            if (firstLoc == null || playerLocation == null) return Integer.MAX_VALUE;
            return firstLoc.distanceTo(playerLocation);
        }));

        // Flatten: nearest area first, easy tasks first within each area
        List<Task> result = new ArrayList<>();
        for (Map.Entry<String, List<Task>> e : areaEntries)
        {
            result.addAll(e.getValue());
        }

        return result;
    }
}
```

- [ ] **Step 8: Run all planner tests**

Run: `./gradlew test --tests "com.leaguesai.agent.*Planner*" --tests "com.leaguesai.agent.*Optimizer*"`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/leaguesai/agent/GoalPlanner.java src/main/java/com/leaguesai/agent/PlannerOptimizer.java src/test/java/com/leaguesai/agent/GoalPlannerTest.java src/test/java/com/leaguesai/agent/PlannerOptimizerTest.java
git commit -m "feat: add GoalPlanner with DAG resolution and PlannerOptimizer"
```

---

## Task 12: TileHighlightOverlay (TDD)

**Files:**
- Create: `src/main/java/com/leaguesai/overlay/TileHighlightOverlay.java`
- Create: `src/test/java/com/leaguesai/overlay/TileHighlightOverlayTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.leaguesai.overlay;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import com.leaguesai.LeaguesAiConfig;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TileHighlightOverlayTest
{
    @Mock private Client client;
    @Mock private LeaguesAiConfig config;

    private TileHighlightOverlay overlay;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        when(config.overlayColor()).thenReturn(Color.CYAN);
        overlay = new TileHighlightOverlay(client, config);
    }

    @Test
    public void testRendersNothingWhenNoTarget()
    {
        overlay.setTargetTile(null);
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        Dimension result = overlay.render(g);

        // Should return null (no rendering)
        assertNull(result);
        g.dispose();
    }

    @Test
    public void testHasTargetWhenSet()
    {
        WorldPoint target = new WorldPoint(3200, 3200, 0);
        overlay.setTargetTile(target);
        assertEquals(target, overlay.getTargetTile());
    }

    @Test
    public void testClearsTarget()
    {
        overlay.setTargetTile(new WorldPoint(3200, 3200, 0));
        overlay.clear();
        assertNull(overlay.getTargetTile());
    }

    @Test
    public void testRendersNothingWhenTargetNotInScene()
    {
        // Target is set but LocalPoint.fromWorld returns null (not in scene)
        overlay.setTargetTile(new WorldPoint(9999, 9999, 0));

        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        Dimension result = overlay.render(g);
        assertNull(result);
        g.dispose();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.overlay.TileHighlightOverlayTest`
Expected: FAIL

- [ ] **Step 3: Implement TileHighlightOverlay**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.*;

@Slf4j
public class TileHighlightOverlay extends Overlay
{
    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    @Setter
    private WorldPoint targetTile;

    @Inject
    public TileHighlightOverlay(Client client, LeaguesAiConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void clear()
    {
        this.targetTile = null;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (targetTile == null)
        {
            return null;
        }

        LocalPoint lp = LocalPoint.fromWorld(client, targetTile);
        if (lp == null)
        {
            return null;
        }

        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null)
        {
            return null;
        }

        Color color = config.overlayColor();
        OverlayUtil.renderPolygon(graphics, poly, color);

        return null;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.leaguesai.overlay.TileHighlightOverlayTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leaguesai/overlay/TileHighlightOverlay.java src/test/java/com/leaguesai/overlay/TileHighlightOverlayTest.java
git commit -m "feat: add TileHighlightOverlay with TDD (Quest Helper parity)"
```

---

## Task 13: ArrowOverlay (TDD)

**Files:**
- Create: `src/main/java/com/leaguesai/overlay/ArrowOverlay.java`
- Create: `src/test/java/com/leaguesai/overlay/ArrowOverlayTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ArrowOverlayTest
{
    @Mock private Client client;
    @Mock private LeaguesAiConfig config;

    private ArrowOverlay overlay;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        when(config.overlayColor()).thenReturn(Color.CYAN);
        overlay = new ArrowOverlay(client, config);
    }

    @Test
    public void testRendersNothingWhenNoTarget()
    {
        overlay.setTargetTile(null);
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        assertNull(overlay.render(g));
        g.dispose();
    }

    @Test
    public void testSetAndClearTarget()
    {
        WorldPoint wp = new WorldPoint(3200, 3200, 0);
        overlay.setTargetTile(wp);
        assertEquals(wp, overlay.getTargetTile());

        overlay.clear();
        assertNull(overlay.getTargetTile());
    }

    @Test
    public void testRendersNothingWhenTargetNotInScene()
    {
        overlay.setTargetTile(new WorldPoint(9999, 9999, 0));
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        assertNull(overlay.render(g));
        g.dispose();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.overlay.ArrowOverlayTest`
Expected: FAIL

- [ ] **Step 3: Implement ArrowOverlay**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;

public class ArrowOverlay extends Overlay
{
    private static final int ARROW_HEIGHT = 40;
    private static final int ARROW_WIDTH = 16;
    private static final int ARROW_Z_OFFSET = 200;

    private final Client client;
    private final LeaguesAiConfig config;

    @Getter @Setter
    private WorldPoint targetTile;

    @Inject
    public ArrowOverlay(Client client, LeaguesAiConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void clear()
    {
        this.targetTile = null;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (targetTile == null)
        {
            return null;
        }

        LocalPoint lp = LocalPoint.fromWorld(client, targetTile);
        if (lp == null)
        {
            return null;
        }

        Polygon tilePoly = Perspective.getCanvasTilePoly(client, lp);
        if (tilePoly == null)
        {
            return null;
        }

        // Get center of tile polygon
        Rectangle bounds = tilePoly.getBounds();
        int centerX = bounds.x + bounds.width / 2;
        int centerY = bounds.y - ARROW_Z_OFFSET;

        drawArrow(graphics, centerX, centerY, config.overlayColor());

        return null;
    }

    private void drawArrow(Graphics2D graphics, int x, int y, Color color)
    {
        int halfWidth = ARROW_WIDTH / 2;

        // Arrow pointing down: triangle
        int[] xPoints = {x - halfWidth, x + halfWidth, x};
        int[] yPoints = {y, y, y + ARROW_HEIGHT};
        Polygon arrow = new Polygon(xPoints, yPoints, 3);

        graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 180));
        graphics.fillPolygon(arrow);
        graphics.setColor(color);
        graphics.setStroke(new BasicStroke(2));
        graphics.drawPolygon(arrow);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.leaguesai.overlay.ArrowOverlayTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leaguesai/overlay/ArrowOverlay.java src/test/java/com/leaguesai/overlay/ArrowOverlayTest.java
git commit -m "feat: add ArrowOverlay with 3D downward arrow (TDD)"
```

---

## Task 14: NpcHighlightOverlay (TDD)

**Files:**
- Create: `src/main/java/com/leaguesai/overlay/NpcHighlightOverlay.java`
- Create: `src/test/java/com/leaguesai/overlay/NpcHighlightOverlayTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class NpcHighlightOverlayTest
{
    @Mock private Client client;
    @Mock private LeaguesAiConfig config;

    private NpcHighlightOverlay overlay;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        when(config.overlayColor()).thenReturn(Color.CYAN);
        when(client.getNpcs()).thenReturn(Collections.emptyList());
        overlay = new NpcHighlightOverlay(client, config);
    }

    @Test
    public void testRendersNothingWhenNoTargetIds()
    {
        overlay.setTargetNpcIds(Collections.emptyList());
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        assertNull(overlay.render(g));
        g.dispose();
    }

    @Test
    public void testSetAndClearTargetIds()
    {
        overlay.setTargetNpcIds(List.of(1234, 5678));
        assertEquals(2, overlay.getTargetNpcIds().size());

        overlay.clear();
        assertTrue(overlay.getTargetNpcIds().isEmpty());
    }

    @Test
    public void testRendersNothingWhenNpcNotInScene()
    {
        overlay.setTargetNpcIds(List.of(1234));
        // No NPCs in client.getNpcs()
        when(client.getNpcs()).thenReturn(Collections.emptyList());

        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        assertNull(overlay.render(g));
        g.dispose();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.overlay.NpcHighlightOverlayTest`
Expected: FAIL

- [ ] **Step 3: Implement NpcHighlightOverlay**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class NpcHighlightOverlay extends Overlay
{
    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private List<Integer> targetNpcIds = Collections.emptyList();

    @Inject
    public NpcHighlightOverlay(Client client, LeaguesAiConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setTargetNpcIds(List<Integer> ids)
    {
        this.targetNpcIds = ids != null ? new ArrayList<>(ids) : Collections.emptyList();
    }

    public void clear()
    {
        this.targetNpcIds = Collections.emptyList();
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (targetNpcIds.isEmpty())
        {
            return null;
        }

        Color color = config.overlayColor();
        boolean rendered = false;

        for (NPC npc : client.getNpcs())
        {
            if (npc == null) continue;
            if (targetNpcIds.contains(npc.getId()))
            {
                Shape hull = npc.getConvexHull();
                if (hull != null)
                {
                    OverlayUtil.renderPolygon(graphics, hull, color);
                    rendered = true;
                }
            }
        }

        return null;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.leaguesai.overlay.NpcHighlightOverlayTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leaguesai/overlay/NpcHighlightOverlay.java src/test/java/com/leaguesai/overlay/NpcHighlightOverlayTest.java
git commit -m "feat: add NpcHighlightOverlay with convex hull rendering (TDD)"
```

---

## Task 15: ObjectHighlightOverlay (TDD)

**Files:**
- Create: `src/main/java/com/leaguesai/overlay/ObjectHighlightOverlay.java`
- Create: `src/test/java/com/leaguesai/overlay/ObjectHighlightOverlayTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ObjectHighlightOverlayTest
{
    @Mock private Client client;
    @Mock private LeaguesAiConfig config;
    @Mock private Scene scene;

    private ObjectHighlightOverlay overlay;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        when(config.overlayColor()).thenReturn(Color.CYAN);
        when(client.getScene()).thenReturn(scene);
        when(scene.getTiles()).thenReturn(new Tile[4][104][104]);
        overlay = new ObjectHighlightOverlay(client, config);
    }

    @Test
    public void testRendersNothingWhenNoTargetIds()
    {
        overlay.setTargetObjectIds(Collections.emptyList());
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        assertNull(overlay.render(g));
        g.dispose();
    }

    @Test
    public void testSetAndClearTargetIds()
    {
        overlay.setTargetObjectIds(List.of(100, 200));
        assertEquals(2, overlay.getTargetObjectIds().size());
        overlay.clear();
        assertTrue(overlay.getTargetObjectIds().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.overlay.ObjectHighlightOverlayTest`
Expected: FAIL

- [ ] **Step 3: Implement ObjectHighlightOverlay**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ObjectHighlightOverlay extends Overlay
{
    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private List<Integer> targetObjectIds = Collections.emptyList();

    @Inject
    public ObjectHighlightOverlay(Client client, LeaguesAiConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setTargetObjectIds(List<Integer> ids)
    {
        this.targetObjectIds = ids != null ? new ArrayList<>(ids) : Collections.emptyList();
    }

    public void clear()
    {
        this.targetObjectIds = Collections.emptyList();
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (targetObjectIds.isEmpty())
        {
            return null;
        }

        Color color = config.overlayColor();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();

        for (int x = 0; x < 104; x++)
        {
            for (int y = 0; y < 104; y++)
            {
                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;

                for (GameObject obj : tile.getGameObjects())
                {
                    if (obj == null) continue;
                    if (targetObjectIds.contains(obj.getId()))
                    {
                        Shape hull = obj.getConvexHull();
                        if (hull != null)
                        {
                            OverlayUtil.renderPolygon(graphics, hull, color);
                        }
                    }
                }
            }
        }

        return null;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.leaguesai.overlay.ObjectHighlightOverlayTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leaguesai/overlay/ObjectHighlightOverlay.java src/test/java/com/leaguesai/overlay/ObjectHighlightOverlayTest.java
git commit -m "feat: add ObjectHighlightOverlay with scene scanning (TDD)"
```

---

## Task 16: GroundItemOverlay, MinimapOverlay, WorldMapOverlay (TDD)

**Files:**
- Create: `src/main/java/com/leaguesai/overlay/GroundItemOverlay.java`
- Create: `src/main/java/com/leaguesai/overlay/MinimapOverlay.java`
- Create: `src/main/java/com/leaguesai/overlay/WorldMapOverlay.java`
- Create: `src/test/java/com/leaguesai/overlay/GroundItemOverlayTest.java`
- Create: `src/test/java/com/leaguesai/overlay/MinimapOverlayTest.java`
- Create: `src/test/java/com/leaguesai/overlay/WorldMapOverlayTest.java`

- [ ] **Step 1: Write failing GroundItemOverlay test**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GroundItemOverlayTest
{
    @Mock private Client client;
    @Mock private LeaguesAiConfig config;

    private GroundItemOverlay overlay;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        when(config.overlayColor()).thenReturn(Color.CYAN);
        overlay = new GroundItemOverlay(client, config);
    }

    @Test
    public void testRendersNothingWhenNoTargets()
    {
        overlay.setTargetItemIds(Collections.emptyList());
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        assertNull(overlay.render(g));
        g.dispose();
    }

    @Test
    public void testSetAndClear()
    {
        overlay.setTargetItemIds(List.of(315, 317));
        assertEquals(2, overlay.getTargetItemIds().size());
        overlay.clear();
        assertTrue(overlay.getTargetItemIds().isEmpty());
    }
}
```

- [ ] **Step 2: Implement GroundItemOverlay**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GroundItemOverlay extends Overlay
{
    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private List<Integer> targetItemIds = Collections.emptyList();

    @Inject
    public GroundItemOverlay(Client client, LeaguesAiConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setTargetItemIds(List<Integer> ids)
    {
        this.targetItemIds = ids != null ? new ArrayList<>(ids) : Collections.emptyList();
    }

    public void clear()
    {
        this.targetItemIds = Collections.emptyList();
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (targetItemIds.isEmpty())
        {
            return null;
        }

        Color color = config.overlayColor();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();

        for (int x = 0; x < 104; x++)
        {
            for (int y = 0; y < 104; y++)
            {
                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;

                List<TileItem> items = tile.getGroundItems();
                if (items == null) continue;

                for (TileItem item : items)
                {
                    if (item != null && targetItemIds.contains(item.getId()))
                    {
                        Polygon poly = Perspective.getCanvasTilePoly(client,
                            tile.getLocalLocation());
                        if (poly != null)
                        {
                            OverlayUtil.renderPolygon(graphics, poly, color);
                        }
                    }
                }
            }
        }

        return null;
    }
}
```

- [ ] **Step 3: Write failing MinimapOverlay test**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class MinimapOverlayTest
{
    @Mock private Client client;
    @Mock private LeaguesAiConfig config;

    private MinimapOverlay overlay;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        when(config.overlayColor()).thenReturn(Color.CYAN);
        overlay = new MinimapOverlay(client, config);
    }

    @Test
    public void testRendersNothingWhenNoTarget()
    {
        overlay.setTargetPoint(null);
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        assertNull(overlay.render(g));
        g.dispose();
    }

    @Test
    public void testSetAndClear()
    {
        overlay.setTargetPoint(new WorldPoint(3200, 3200, 0));
        assertNotNull(overlay.getTargetPoint());
        overlay.clear();
        assertNull(overlay.getTargetPoint());
    }
}
```

- [ ] **Step 4: Implement MinimapOverlay**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.*;

public class MinimapOverlay extends Overlay
{
    private final Client client;
    private final LeaguesAiConfig config;

    @Getter @Setter
    private WorldPoint targetPoint;

    @Inject
    public MinimapOverlay(Client client, LeaguesAiConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
    }

    public void clear()
    {
        this.targetPoint = null;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (targetPoint == null)
        {
            return null;
        }

        LocalPoint lp = LocalPoint.fromWorld(client, targetPoint);
        if (lp == null)
        {
            return null;
        }

        Point minimapPoint = Perspective.localToMinimap(client, lp);
        if (minimapPoint == null)
        {
            return null;
        }

        OverlayUtil.renderMinimapLocation(graphics, minimapPoint, config.overlayColor());

        return null;
    }
}
```

(Note: `Perspective.localToMinimap` needs the correct import — use `net.runelite.api.Perspective`.)

- [ ] **Step 5: Write failing WorldMapOverlay test and implement**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WorldMapOverlayTest
{
    @Mock private Client client;
    @Mock private LeaguesAiConfig config;

    private WorldMapOverlay overlay;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        when(config.overlayColor()).thenReturn(Color.CYAN);
        overlay = new WorldMapOverlay(client, config);
    }

    @Test
    public void testSetAndClear()
    {
        overlay.setTargetPoint(new WorldPoint(3200, 3200, 0));
        assertNotNull(overlay.getTargetPoint());
        overlay.clear();
        assertNull(overlay.getTargetPoint());
    }
}
```

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

import javax.inject.Inject;
import java.awt.image.BufferedImage;

public class WorldMapOverlay
{
    private final Client client;
    private final LeaguesAiConfig config;
    private final WorldMapPointManager worldMapPointManager;

    @Getter @Setter
    private WorldPoint targetPoint;
    private WorldMapPoint currentMapPoint;

    @Inject
    public WorldMapOverlay(Client client, LeaguesAiConfig config,
                           WorldMapPointManager worldMapPointManager)
    {
        this.client = client;
        this.config = config;
        this.worldMapPointManager = worldMapPointManager;
    }

    // Constructor for tests without WorldMapPointManager
    public WorldMapOverlay(Client client, LeaguesAiConfig config)
    {
        this.client = client;
        this.config = config;
        this.worldMapPointManager = null;
    }

    public void update(WorldPoint point, BufferedImage icon)
    {
        clear();
        if (point == null || worldMapPointManager == null) return;

        this.targetPoint = point;
        currentMapPoint = new WorldMapPoint(point, icon);
        currentMapPoint.setTooltip("Leagues AI Target");
        worldMapPointManager.add(currentMapPoint);
    }

    public void clear()
    {
        this.targetPoint = null;
        if (currentMapPoint != null && worldMapPointManager != null)
        {
            worldMapPointManager.remove(currentMapPoint);
            currentMapPoint = null;
        }
    }
}
```

- [ ] **Step 6: Run all overlay tests**

Run: `./gradlew test --tests "com.leaguesai.overlay.*"`
Expected: All PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/leaguesai/overlay/GroundItemOverlay.java src/main/java/com/leaguesai/overlay/MinimapOverlay.java src/main/java/com/leaguesai/overlay/WorldMapOverlay.java src/test/java/com/leaguesai/overlay/
git commit -m "feat: add GroundItem, Minimap, and WorldMap overlays (TDD)"
```

---

## Task 17: PathOverlay and WidgetOverlay (TDD)

**Files:**
- Create: `src/main/java/com/leaguesai/overlay/PathOverlay.java`
- Create: `src/main/java/com/leaguesai/overlay/WidgetOverlay.java`
- Create: `src/test/java/com/leaguesai/overlay/PathOverlayTest.java`
- Create: `src/test/java/com/leaguesai/overlay/WidgetOverlayTest.java`

- [ ] **Step 1: Write failing PathOverlay test**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PathOverlayTest
{
    @Mock private Client client;
    @Mock private LeaguesAiConfig config;

    private PathOverlay overlay;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        when(config.overlayColor()).thenReturn(Color.CYAN);
        overlay = new PathOverlay(client, config);
    }

    @Test
    public void testRendersNothingWhenNoPath()
    {
        overlay.setPathPoints(Collections.emptyList());
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        assertNull(overlay.render(g));
        g.dispose();
    }

    @Test
    public void testSetAndClear()
    {
        List<WorldPoint> path = List.of(
            new WorldPoint(3200, 3200, 0),
            new WorldPoint(3210, 3200, 0)
        );
        overlay.setPathPoints(path);
        assertEquals(2, overlay.getPathPoints().size());
        overlay.clear();
        assertTrue(overlay.getPathPoints().isEmpty());
    }
}
```

- [ ] **Step 2: Implement PathOverlay**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PathOverlay extends Overlay
{
    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private List<WorldPoint> pathPoints = Collections.emptyList();

    @Inject
    public PathOverlay(Client client, LeaguesAiConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void setPathPoints(List<WorldPoint> points)
    {
        this.pathPoints = points != null ? new ArrayList<>(points) : Collections.emptyList();
    }

    public void clear()
    {
        this.pathPoints = Collections.emptyList();
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (pathPoints.size() < 2)
        {
            return null;
        }

        Color color = config.overlayColor();
        graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 120));
        graphics.setStroke(new BasicStroke(2));

        for (int i = 0; i < pathPoints.size() - 1; i++)
        {
            LocalPoint lp1 = LocalPoint.fromWorld(client, pathPoints.get(i));
            LocalPoint lp2 = LocalPoint.fromWorld(client, pathPoints.get(i + 1));

            if (lp1 == null || lp2 == null) continue;

            Polygon poly1 = Perspective.getCanvasTilePoly(client, lp1);
            Polygon poly2 = Perspective.getCanvasTilePoly(client, lp2);

            if (poly1 == null || poly2 == null) continue;

            Rectangle b1 = poly1.getBounds();
            Rectangle b2 = poly2.getBounds();

            graphics.drawLine(
                b1.x + b1.width / 2, b1.y + b1.height / 2,
                b2.x + b2.width / 2, b2.y + b2.height / 2
            );
        }

        return null;
    }
}
```

- [ ] **Step 3: Write failing WidgetOverlay test and implement**

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WidgetOverlayTest
{
    @Mock private Client client;
    @Mock private LeaguesAiConfig config;

    private WidgetOverlay overlay;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        when(config.overlayColor()).thenReturn(Color.CYAN);
        overlay = new WidgetOverlay(client, config);
    }

    @Test
    public void testRendersNothingWhenNoTargets()
    {
        overlay.setTargetWidgetIds(Collections.emptyList());
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        assertNull(overlay.render(g));
        g.dispose();
    }

    @Test
    public void testSetAndClear()
    {
        overlay.setTargetWidgetIds(List.of(100, 200));
        assertEquals(2, overlay.getTargetWidgetIds().size());
        overlay.clear();
        assertTrue(overlay.getTargetWidgetIds().isEmpty());
    }
}
```

```java
package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WidgetOverlay extends Overlay
{
    private final Client client;
    private final LeaguesAiConfig config;

    @Getter
    private List<Integer> targetWidgetIds = Collections.emptyList();

    @Inject
    public WidgetOverlay(Client client, LeaguesAiConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    public void setTargetWidgetIds(List<Integer> ids)
    {
        this.targetWidgetIds = ids != null ? new ArrayList<>(ids) : Collections.emptyList();
    }

    public void clear()
    {
        this.targetWidgetIds = Collections.emptyList();
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (targetWidgetIds.isEmpty())
        {
            return null;
        }

        Color color = config.overlayColor();

        for (int widgetId : targetWidgetIds)
        {
            int groupId = widgetId >> 16;
            int childId = widgetId & 0xFFFF;
            Widget widget = client.getWidget(groupId, childId);

            if (widget == null || widget.isHidden()) continue;

            Rectangle bounds = widget.getBounds();
            if (bounds == null) continue;

            graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            graphics.setColor(color);
            graphics.setStroke(new BasicStroke(2));
            graphics.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        return null;
    }
}
```

- [ ] **Step 4: Run all overlay tests**

Run: `./gradlew test --tests "com.leaguesai.overlay.*"`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leaguesai/overlay/PathOverlay.java src/main/java/com/leaguesai/overlay/WidgetOverlay.java src/test/java/com/leaguesai/overlay/PathOverlayTest.java src/test/java/com/leaguesai/overlay/WidgetOverlayTest.java
git commit -m "feat: add PathOverlay and WidgetOverlay (TDD)"
```

---

## Task 18: OverlayController (TDD)

**Files:**
- Create: `src/main/java/com/leaguesai/overlay/OverlayController.java`
- Create: `src/test/java/com/leaguesai/overlay/OverlayControllerTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.leaguesai.overlay;

import com.leaguesai.agent.PlannedStep;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import com.leaguesai.ui.AnimationType;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OverlayControllerTest
{
    @Mock private TileHighlightOverlay tileOverlay;
    @Mock private ArrowOverlay arrowOverlay;
    @Mock private NpcHighlightOverlay npcOverlay;
    @Mock private ObjectHighlightOverlay objectOverlay;
    @Mock private GroundItemOverlay groundItemOverlay;
    @Mock private MinimapOverlay minimapOverlay;
    @Mock private WorldMapOverlay worldMapOverlay;
    @Mock private PathOverlay pathOverlay;
    @Mock private WidgetOverlay widgetOverlay;

    private OverlayController controller;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        controller = new OverlayController(
            tileOverlay, arrowOverlay, npcOverlay, objectOverlay,
            groundItemOverlay, minimapOverlay, worldMapOverlay,
            pathOverlay, widgetOverlay
        );
    }

    @Test
    public void testSetActiveStepActivatesOverlays()
    {
        WorldPoint dest = new WorldPoint(3200, 3200, 0);
        OverlayData data = OverlayData.builder()
            .targetTile(dest)
            .targetNpcIds(List.of(1234))
            .targetObjectIds(Collections.emptyList())
            .targetItemIds(Collections.emptyList())
            .showArrow(true)
            .showMinimap(true)
            .build();

        PlannedStep step = PlannedStep.builder()
            .task(Task.builder().id("t1").name("Test").difficulty(Difficulty.EASY)
                .points(10).area("misthalin").build())
            .destination(dest)
            .instruction("Go fish")
            .overlayData(data)
            .animation(AnimationType.FISHING)
            .build();

        controller.setActiveStep(step);

        verify(tileOverlay).setTargetTile(dest);
        verify(arrowOverlay).setTargetTile(dest);
        verify(npcOverlay).setTargetNpcIds(List.of(1234));
        verify(minimapOverlay).setTargetPoint(dest);
    }

    @Test
    public void testClearAllClearsEveryOverlay()
    {
        controller.clearAll();

        verify(tileOverlay).clear();
        verify(arrowOverlay).clear();
        verify(npcOverlay).clear();
        verify(objectOverlay).clear();
        verify(groundItemOverlay).clear();
        verify(minimapOverlay).clear();
        verify(worldMapOverlay).clear();
        verify(pathOverlay).clear();
        verify(widgetOverlay).clear();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.overlay.OverlayControllerTest`
Expected: FAIL

- [ ] **Step 3: Implement OverlayController**

```java
package com.leaguesai.overlay;

import com.leaguesai.agent.PlannedStep;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class OverlayController
{
    private final TileHighlightOverlay tileOverlay;
    private final ArrowOverlay arrowOverlay;
    private final NpcHighlightOverlay npcOverlay;
    private final ObjectHighlightOverlay objectOverlay;
    private final GroundItemOverlay groundItemOverlay;
    private final MinimapOverlay minimapOverlay;
    private final WorldMapOverlay worldMapOverlay;
    private final PathOverlay pathOverlay;
    private final WidgetOverlay widgetOverlay;

    @Inject
    public OverlayController(
        TileHighlightOverlay tileOverlay,
        ArrowOverlay arrowOverlay,
        NpcHighlightOverlay npcOverlay,
        ObjectHighlightOverlay objectOverlay,
        GroundItemOverlay groundItemOverlay,
        MinimapOverlay minimapOverlay,
        WorldMapOverlay worldMapOverlay,
        PathOverlay pathOverlay,
        WidgetOverlay widgetOverlay)
    {
        this.tileOverlay = tileOverlay;
        this.arrowOverlay = arrowOverlay;
        this.npcOverlay = npcOverlay;
        this.objectOverlay = objectOverlay;
        this.groundItemOverlay = groundItemOverlay;
        this.minimapOverlay = minimapOverlay;
        this.worldMapOverlay = worldMapOverlay;
        this.pathOverlay = pathOverlay;
        this.widgetOverlay = widgetOverlay;
    }

    public void setActiveStep(PlannedStep step)
    {
        clearAll();

        if (step == null || step.getOverlayData() == null)
        {
            return;
        }

        OverlayData data = step.getOverlayData();

        if (data.getTargetTile() != null)
        {
            tileOverlay.setTargetTile(data.getTargetTile());

            if (data.isShowArrow())
            {
                arrowOverlay.setTargetTile(data.getTargetTile());
            }

            if (data.isShowMinimap())
            {
                minimapOverlay.setTargetPoint(data.getTargetTile());
            }
        }

        if (data.getTargetNpcIds() != null && !data.getTargetNpcIds().isEmpty())
        {
            npcOverlay.setTargetNpcIds(data.getTargetNpcIds());
        }

        if (data.getTargetObjectIds() != null && !data.getTargetObjectIds().isEmpty())
        {
            objectOverlay.setTargetObjectIds(data.getTargetObjectIds());
        }

        if (data.getTargetItemIds() != null && !data.getTargetItemIds().isEmpty())
        {
            groundItemOverlay.setTargetItemIds(data.getTargetItemIds());
        }

        if (data.getPathPoints() != null && !data.getPathPoints().isEmpty())
        {
            pathOverlay.setPathPoints(data.getPathPoints());
        }

        if (data.getWidgetIds() != null && !data.getWidgetIds().isEmpty())
        {
            widgetOverlay.setTargetWidgetIds(data.getWidgetIds());
        }
    }

    public void clearAll()
    {
        tileOverlay.clear();
        arrowOverlay.clear();
        npcOverlay.clear();
        objectOverlay.clear();
        groundItemOverlay.clear();
        minimapOverlay.clear();
        worldMapOverlay.clear();
        pathOverlay.clear();
        widgetOverlay.clear();
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.leaguesai.overlay.OverlayControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leaguesai/overlay/OverlayController.java src/test/java/com/leaguesai/overlay/OverlayControllerTest.java
git commit -m "feat: add OverlayController that wires PlannedStep to all overlays"
```

---

## Task 19: ASCII Sprite Animation System

**Files:**
- Create: `src/main/java/com/leaguesai/ui/SpriteAnimation.java`
- Create: `src/main/java/com/leaguesai/ui/AsciiSpriteRenderer.java`
- Create: `src/main/java/com/leaguesai/ui/AnimationResolver.java`
- Create: `src/test/java/com/leaguesai/ui/SpriteAnimationTest.java`
- Create: `src/test/java/com/leaguesai/ui/AnimationResolverTest.java`
- Create: `src/main/resources/sprites/idle.txt`

- [ ] **Step 1: Write failing SpriteAnimation test**

```java
package com.leaguesai.ui;

import org.junit.Test;

import static org.junit.Assert.*;

public class SpriteAnimationTest
{
    @Test
    public void testParseFrames()
    {
        String content = "  O\n /|\\\n / \\\n---\n  O\n /|\\\n  |";
        SpriteAnimation anim = SpriteAnimation.parse(content);

        assertEquals(2, anim.getFrameCount());
        assertEquals("  O", anim.getFrame(0)[0]);
        assertEquals(" /|\\", anim.getFrame(0)[1]);
    }

    @Test
    public void testCyclesFrames()
    {
        String content = "A\n---\nB\n---\nC";
        SpriteAnimation anim = SpriteAnimation.parse(content);

        assertEquals("A", anim.getFrame(0)[0]);
        assertEquals("B", anim.getFrame(1)[0]);
        assertEquals("C", anim.getFrame(2)[0]);
        // Wrap around
        assertEquals("A", anim.getFrame(3)[0]);
    }

    @Test
    public void testEmptyContent()
    {
        SpriteAnimation anim = SpriteAnimation.parse("");
        assertEquals(1, anim.getFrameCount());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.leaguesai.ui.SpriteAnimationTest`
Expected: FAIL

- [ ] **Step 3: Implement SpriteAnimation**

```java
package com.leaguesai.ui;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

public class SpriteAnimation
{
    @Getter
    private final List<String[]> frames;

    private SpriteAnimation(List<String[]> frames)
    {
        this.frames = frames;
    }

    public int getFrameCount()
    {
        return frames.size();
    }

    public String[] getFrame(int index)
    {
        return frames.get(index % frames.size());
    }

    public static SpriteAnimation parse(String content)
    {
        if (content == null || content.trim().isEmpty())
        {
            return new SpriteAnimation(List.of(new String[]{""}));
        }

        List<String[]> frames = new ArrayList<>();
        StringBuilder currentFrame = new StringBuilder();

        for (String line : content.split("\n"))
        {
            if (line.equals("---"))
            {
                if (currentFrame.length() > 0)
                {
                    frames.add(currentFrame.toString().split("\n"));
                    currentFrame = new StringBuilder();
                }
            }
            else
            {
                if (currentFrame.length() > 0) currentFrame.append("\n");
                currentFrame.append(line);
            }
        }

        if (currentFrame.length() > 0)
        {
            frames.add(currentFrame.toString().split("\n"));
        }

        if (frames.isEmpty())
        {
            frames.add(new String[]{""});
        }

        return new SpriteAnimation(frames);
    }

    public static SpriteAnimation loadFromResource(String resourcePath)
    {
        try
        {
            var stream = SpriteAnimation.class.getResourceAsStream("/" + resourcePath);
            if (stream == null) return parse("");
            String content = new String(stream.readAllBytes());
            return parse(content);
        }
        catch (Exception e)
        {
            return parse("");
        }
    }
}
```

- [ ] **Step 4: Run SpriteAnimation tests**

Run: `./gradlew test --tests com.leaguesai.ui.SpriteAnimationTest`
Expected: PASS

- [ ] **Step 5: Create idle.txt sprite**

```
   O
  /|\
  / \
---
   O
  /|\
  /|\
---
   O
  /|\
  / \
---
   O
  \|/
  / \
```

Save to `src/main/resources/sprites/idle.txt`.

- [ ] **Step 6: Create remaining sprite files**

Create each file under `src/main/resources/sprites/`:

**walking.txt:**
```
   O
  /|\
  / \
---
   O
  /|\
 /   \
---
   O
  /|\
  / \
---
   O
  /|\
/     \
```

**banking.txt:**
```
   O    _
  /|\ _| |
  / \ |__|
---
   O    _
  /|\ | _|
  / \ |__|
---
   O    _
  \|/ _| |
  / \ |__|
```

**melee.txt:**
```
   O  /
  /|\/
  / \
---
   O /
  /|/
  / \
---
   O
  /|\--
  / \
---
   O  /
  /|\/
  / \
```

**ranged.txt:**
```
   O
  /|\-->
  / \
---
   O
  /|\ ->
  / \
---
   O
  /|\-->
  / \
---
   O
  /|\   >
  / \
```

**mage.txt:**
```
   O  *
  /|\ |
  / \ |
---
   O **
  /|\*
  / \
---
   O ***
  /|\
  / \
---
   O
  /|\  *
  / \  *
```

**cooking.txt:**
```
   O  ~
  /|\ |
  / \ =
---
   O ~~
  /|\ |
  / \ =
---
   O~~~
  /|\ |
  / \ =
---
   O ~
  /|\~|
  / \ =
```

**fishing.txt:**
```
   O  __/
  /|\/
  / \
---
   O  __/
  /|\/  .
  / \
---
   O  __/
  /|\/
  / \ .
---
   O  __/
  /|\/  o
  / \
```

**woodcutting.txt:**
```
   O  /|
  /|\/ |
  / \  |
---
   O /|
  /|/ |
  / \  |
---
   O  /|
  /|\/ |
  / \  |
---
   O/|
  /| |
  / \  |
```

**mining.txt:**
```
   O
  /|\  /
  / \\/
---
   O
  /|\ /
  / \/
---
   O
  /|\  /
  / \\/
---
   O   /
  /|\ /
  / \/
```

- [ ] **Step 7: Implement AsciiSpriteRenderer**

```java
package com.leaguesai.ui;

import javax.swing.*;
import java.awt.*;
import java.util.EnumMap;
import java.util.Map;

public class AsciiSpriteRenderer extends JPanel
{
    private static final Font SPRITE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 12);

    private final Map<AnimationType, SpriteAnimation> animations = new EnumMap<>(AnimationType.class);
    private AnimationType currentType = AnimationType.IDLE;
    private int currentFrame = 0;
    private Timer timer;

    public AsciiSpriteRenderer(int frameIntervalMs)
    {
        setBackground(new Color(30, 30, 30));
        setPreferredSize(new Dimension(200, 80));
        loadAnimations();
        startTimer(frameIntervalMs);
    }

    private void loadAnimations()
    {
        for (AnimationType type : AnimationType.values())
        {
            String filename = "sprites/" + type.name().toLowerCase() + ".txt";
            animations.put(type, SpriteAnimation.loadFromResource(filename));
        }
    }

    private void startTimer(int intervalMs)
    {
        timer = new Timer(intervalMs, e -> {
            currentFrame++;
            repaint();
        });
        timer.start();
    }

    public void setAnimation(AnimationType type)
    {
        if (type != currentType)
        {
            currentType = type;
            currentFrame = 0;
        }
    }

    public void pause()
    {
        if (timer != null) timer.stop();
    }

    public void resume()
    {
        if (timer != null) timer.start();
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setFont(SPRITE_FONT);
        g2.setColor(Color.GREEN);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        SpriteAnimation anim = animations.get(currentType);
        if (anim == null) return;

        String[] frame = anim.getFrame(currentFrame);
        FontMetrics fm = g2.getFontMetrics();
        int lineHeight = fm.getHeight();
        int y = 15;

        for (String line : frame)
        {
            int x = (getWidth() - fm.stringWidth(line)) / 2;
            g2.drawString(line, x, y);
            y += lineHeight;
        }
    }
}
```

- [ ] **Step 8: Write failing AnimationResolver test**

```java
package com.leaguesai.ui;

import com.leaguesai.data.model.Task;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

public class AnimationResolverTest
{
    @Test
    public void testResolveFishing()
    {
        AnimationType type = AnimationResolver.resolve("skilling", "fishing", Set.of(), false);
        assertEquals(AnimationType.FISHING, type);
    }

    @Test
    public void testResolveMeleeCombat()
    {
        AnimationType type = AnimationResolver.resolve("combat", null,
            Set.of(4151), false); // whip
        assertEquals(AnimationType.MELEE, type);
    }

    @Test
    public void testResolveIdleWhenNoTask()
    {
        AnimationType type = AnimationResolver.resolve(null, null, Set.of(), false);
        assertEquals(AnimationType.IDLE, type);
    }

    @Test
    public void testResolveWalkingWhenInTransit()
    {
        AnimationType type = AnimationResolver.resolve("skilling", "fishing", Set.of(), true);
        assertEquals(AnimationType.WALKING, type);
    }
}
```

- [ ] **Step 9: Implement AnimationResolver**

```java
package com.leaguesai.ui;

import java.util.Set;

public class AnimationResolver
{
    // Common weapon IDs for classification
    private static final Set<Integer> RANGED_WEAPONS = Set.of(
        839, 841, 843, 845, 847, 849, 851, 853, 855, 857, 859, 861,  // bows
        4212, 4214, 4734, 11235, // crossbows, dark bow
        10280, 10282, 10284 // crystal bow
    );

    private static final Set<Integer> MAGIC_WEAPONS = Set.of(
        1381, 1383, 1385, 1387, 1389, 1391, 1393, 1395, 1397, 1399, 1401, // staves
        4675, 4710, 6562, 11791, 11998, 12904 // various magic weapons
    );

    public static AnimationType resolve(String taskCategory, String taskSkill,
                                         Set<Integer> equippedItemIds, boolean inTransit)
    {
        if (taskCategory == null)
        {
            return AnimationType.IDLE;
        }

        if (inTransit)
        {
            return AnimationType.WALKING;
        }

        if ("combat".equalsIgnoreCase(taskCategory))
        {
            for (int id : equippedItemIds)
            {
                if (RANGED_WEAPONS.contains(id)) return AnimationType.RANGED;
                if (MAGIC_WEAPONS.contains(id)) return AnimationType.MAGE;
            }
            return AnimationType.MELEE;
        }

        if (taskSkill != null)
        {
            switch (taskSkill.toLowerCase())
            {
                case "cooking": return AnimationType.COOKING;
                case "fishing": return AnimationType.FISHING;
                case "woodcutting": return AnimationType.WOODCUTTING;
                case "mining": return AnimationType.MINING;
            }
        }

        // Check if near a bank
        if ("banking".equalsIgnoreCase(taskCategory))
        {
            return AnimationType.BANKING;
        }

        return AnimationType.IDLE;
    }
}
```

- [ ] **Step 10: Run all UI tests**

Run: `./gradlew test --tests "com.leaguesai.ui.*"`
Expected: All PASS

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/leaguesai/ui/ src/main/resources/sprites/ src/test/java/com/leaguesai/ui/
git commit -m "feat: add ASCII sprite animation system with 10 animations"
```

---

## Task 20: UI Panel (LeaguesAiPanel, ChatPanel, AdvicePanel, SettingsPanel)

**Files:**
- Create: `src/main/java/com/leaguesai/ui/LeaguesAiPanel.java`
- Create: `src/main/java/com/leaguesai/ui/ChatPanel.java`
- Create: `src/main/java/com/leaguesai/ui/AdvicePanel.java`
- Create: `src/main/java/com/leaguesai/ui/SettingsPanel.java`

- [ ] **Step 1: Create ChatPanel**

```java
package com.leaguesai.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

public class ChatPanel extends JPanel
{
    private final JTextArea chatHistory;
    private final JTextField inputField;
    private Consumer<String> onSendMessage;

    public ChatPanel()
    {
        setLayout(new BorderLayout());
        setBackground(new Color(30, 30, 30));

        chatHistory = new JTextArea();
        chatHistory.setEditable(false);
        chatHistory.setLineWrap(true);
        chatHistory.setWrapStyleWord(true);
        chatHistory.setBackground(new Color(40, 40, 40));
        chatHistory.setForeground(Color.WHITE);
        chatHistory.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        chatHistory.setBorder(new EmptyBorder(5, 5, 5, 5));

        JScrollPane scrollPane = new JScrollPane(chatHistory);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        add(scrollPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout(4, 0));
        inputPanel.setBackground(new Color(30, 30, 30));
        inputPanel.setBorder(new EmptyBorder(4, 0, 0, 0));

        inputField = new JTextField();
        inputField.setBackground(new Color(50, 50, 50));
        inputField.setForeground(Color.WHITE);
        inputField.setCaretColor(Color.WHITE);

        JButton sendButton = new JButton("Send");
        sendButton.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        add(inputPanel, BorderLayout.SOUTH);
    }

    private void sendMessage()
    {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        inputField.setText("");
        appendMessage("You", text);
        if (onSendMessage != null) onSendMessage.accept(text);
    }

    public void appendMessage(String sender, String message)
    {
        chatHistory.append(sender + ": " + message + "\n\n");
        chatHistory.setCaretPosition(chatHistory.getDocument().getLength());
    }

    public void setOnSendMessage(Consumer<String> handler)
    {
        this.onSendMessage = handler;
    }
}
```

- [ ] **Step 2: Create AdvicePanel**

```java
package com.leaguesai.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class AdvicePanel extends JPanel
{
    private final JTextArea adviceText;
    private final JLabel goalLabel;
    private final JLabel progressLabel;
    private final JTextArea nextStepsText;
    private Runnable onRefresh;

    public AdvicePanel()
    {
        setLayout(new BorderLayout());
        setBackground(new Color(30, 30, 30));

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(new Color(30, 30, 30));
        topPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        goalLabel = new JLabel("Goal: (none set)");
        goalLabel.setForeground(Color.CYAN);
        goalLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));

        progressLabel = new JLabel("Progress: 0/0 tasks");
        progressLabel.setForeground(Color.LIGHT_GRAY);
        progressLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));

        topPanel.add(goalLabel);
        topPanel.add(Box.createVerticalStrut(4));
        topPanel.add(progressLabel);

        add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBackground(new Color(30, 30, 30));

        adviceText = new JTextArea();
        adviceText.setEditable(false);
        adviceText.setLineWrap(true);
        adviceText.setWrapStyleWord(true);
        adviceText.setBackground(new Color(40, 40, 40));
        adviceText.setForeground(Color.WHITE);
        adviceText.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        adviceText.setBorder(new EmptyBorder(5, 5, 5, 5));
        adviceText.setText("Click 'Refresh' to get advice based on your current state.");

        JScrollPane scrollPane = new JScrollPane(adviceText);
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        nextStepsText = new JTextArea(3, 20);
        nextStepsText.setEditable(false);
        nextStepsText.setBackground(new Color(35, 35, 35));
        nextStepsText.setForeground(Color.YELLOW);
        nextStepsText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        nextStepsText.setBorder(new EmptyBorder(5, 5, 5, 5));
        centerPanel.add(nextStepsText, BorderLayout.SOUTH);

        add(centerPanel, BorderLayout.CENTER);

        JButton refreshButton = new JButton("Refresh Advice");
        refreshButton.addActionListener(e -> { if (onRefresh != null) onRefresh.run(); });
        add(refreshButton, BorderLayout.SOUTH);
    }

    public void setAdvice(String advice) { adviceText.setText(advice); }
    public void setGoal(String goal) { goalLabel.setText("Goal: " + goal); }
    public void setProgress(int completed, int total) { progressLabel.setText("Progress: " + completed + "/" + total + " tasks"); }
    public void setNextSteps(String steps) { nextStepsText.setText(steps); }
    public void setOnRefresh(Runnable handler) { this.onRefresh = handler; }
}
```

- [ ] **Step 3: Create SettingsPanel**

```java
package com.leaguesai.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

public class SettingsPanel extends JPanel
{
    private final JPasswordField apiKeyField;
    private final JCheckBox autoModeToggle;
    private final JTextField goalField;
    private Consumer<String> onGoalSet;
    private Consumer<String> onApiKeyChanged;
    private Runnable onRefreshData;

    public SettingsPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(30, 30, 30));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // API Key
        add(createLabel("OpenAI API Key:"));
        apiKeyField = new JPasswordField();
        apiKeyField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        apiKeyField.setBackground(new Color(50, 50, 50));
        apiKeyField.setForeground(Color.WHITE);
        add(apiKeyField);
        add(Box.createVerticalStrut(10));

        // Auto Mode
        autoModeToggle = new JCheckBox("Auto Mode (auto-advance tasks)");
        autoModeToggle.setForeground(Color.WHITE);
        autoModeToggle.setBackground(new Color(30, 30, 30));
        autoModeToggle.setSelected(true);
        add(autoModeToggle);
        add(Box.createVerticalStrut(10));

        // Goal
        add(createLabel("Current Goal:"));
        JPanel goalPanel = new JPanel(new BorderLayout(4, 0));
        goalPanel.setBackground(new Color(30, 30, 30));
        goalPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));

        goalField = new JTextField();
        goalField.setBackground(new Color(50, 50, 50));
        goalField.setForeground(Color.WHITE);
        goalField.setCaretColor(Color.WHITE);

        JButton setGoalButton = new JButton("Set");
        setGoalButton.addActionListener(e -> {
            if (onGoalSet != null) onGoalSet.accept(goalField.getText().trim());
        });

        goalPanel.add(goalField, BorderLayout.CENTER);
        goalPanel.add(setGoalButton, BorderLayout.EAST);
        add(goalPanel);
        add(Box.createVerticalStrut(20));

        // Refresh Data
        JButton refreshDataButton = new JButton("Refresh Task Data");
        refreshDataButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        refreshDataButton.addActionListener(e -> { if (onRefreshData != null) onRefreshData.run(); });
        add(refreshDataButton);
    }

    private JLabel createLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(Color.LIGHT_GRAY);
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    public String getApiKey() { return new String(apiKeyField.getPassword()); }
    public boolean isAutoMode() { return autoModeToggle.isSelected(); }
    public void setGoalText(String goal) { goalField.setText(goal); }
    public void setOnGoalSet(Consumer<String> handler) { this.onGoalSet = handler; }
    public void setOnApiKeyChanged(Consumer<String> handler) { this.onApiKeyChanged = handler; }
    public void setOnRefreshData(Runnable handler) { this.onRefreshData = handler; }
}
```

- [ ] **Step 4: Create LeaguesAiPanel (main panel)**

```java
package com.leaguesai.ui;

import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class LeaguesAiPanel extends PluginPanel
{
    private final AsciiSpriteRenderer spriteRenderer;
    private final JLabel statusLabel;
    private final JLabel progressLabel;
    private final ChatPanel chatPanel;
    private final AdvicePanel advicePanel;
    private final SettingsPanel settingsPanel;
    private final CardLayout tabCardLayout;
    private final JPanel tabContent;

    public LeaguesAiPanel(int animationSpeed)
    {
        super(false);
        setLayout(new BorderLayout());
        setBackground(new Color(30, 30, 30));

        // Top: Sprite + Status
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(new Color(30, 30, 30));
        topPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        spriteRenderer = new AsciiSpriteRenderer(animationSpeed);
        spriteRenderer.setAlignmentX(Component.CENTER_ALIGNMENT);
        topPanel.add(spriteRenderer);

        statusLabel = new JLabel("Idle");
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        topPanel.add(Box.createVerticalStrut(5));
        topPanel.add(statusLabel);

        progressLabel = new JLabel("0/0 tasks");
        progressLabel.setForeground(Color.LIGHT_GRAY);
        progressLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        progressLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        topPanel.add(progressLabel);

        add(topPanel, BorderLayout.NORTH);

        // Tab buttons
        JPanel tabBar = new JPanel(new GridLayout(1, 3, 2, 0));
        tabBar.setBackground(new Color(30, 30, 30));
        tabBar.setBorder(new EmptyBorder(5, 5, 5, 5));

        JButton chatBtn = new JButton("Chat");
        JButton adviceBtn = new JButton("Advice");
        JButton settingsBtn = new JButton("Settings");

        chatBtn.addActionListener(e -> showTab("chat"));
        adviceBtn.addActionListener(e -> showTab("advice"));
        settingsBtn.addActionListener(e -> showTab("settings"));

        tabBar.add(chatBtn);
        tabBar.add(adviceBtn);
        tabBar.add(settingsBtn);

        // Tab content
        tabCardLayout = new CardLayout();
        tabContent = new JPanel(tabCardLayout);
        tabContent.setBackground(new Color(30, 30, 30));

        chatPanel = new ChatPanel();
        advicePanel = new AdvicePanel();
        settingsPanel = new SettingsPanel();

        tabContent.add(chatPanel, "chat");
        tabContent.add(advicePanel, "advice");
        tabContent.add(settingsPanel, "settings");

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(new Color(30, 30, 30));
        bottomPanel.add(tabBar, BorderLayout.NORTH);
        bottomPanel.add(tabContent, BorderLayout.CENTER);

        add(bottomPanel, BorderLayout.CENTER);
    }

    private void showTab(String name)
    {
        tabCardLayout.show(tabContent, name);
    }

    public AsciiSpriteRenderer getSpriteRenderer() { return spriteRenderer; }
    public ChatPanel getChatPanel() { return chatPanel; }
    public AdvicePanel getAdvicePanel() { return advicePanel; }
    public SettingsPanel getSettingsPanel() { return settingsPanel; }

    public void setStatus(String status) { statusLabel.setText(status); }
    public void setProgress(int completed, int total) { progressLabel.setText(completed + "/" + total + " tasks"); }
}
```

- [ ] **Step 5: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/leaguesai/ui/
git commit -m "feat: add UI panel with ASCII sprite, chat, advice, and settings tabs"
```

---

## Task 21: ChatService and AdviceService

**Files:**
- Create: `src/main/java/com/leaguesai/agent/ChatService.java`
- Create: `src/main/java/com/leaguesai/agent/AdviceService.java`

- [ ] **Step 1: Implement ChatService**

```java
package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.VectorIndex;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Singleton
public class ChatService
{
    private final OpenAiClient openAiClient;
    private final PlayerContextAssembler contextAssembler;
    private final TaskRepository taskRepo;
    private final VectorIndex vectorIndex;
    private final List<OpenAiClient.Message> conversationHistory = new ArrayList<>();
    private static final int MAX_HISTORY = 20;

    @Inject
    public ChatService(OpenAiClient openAiClient, PlayerContextAssembler contextAssembler,
                       TaskRepository taskRepo, VectorIndex vectorIndex)
    {
        this.openAiClient = openAiClient;
        this.contextAssembler = contextAssembler;
        this.taskRepo = taskRepo;
        this.vectorIndex = vectorIndex;
    }

    public String sendMessage(String userMessage) throws Exception
    {
        conversationHistory.add(new OpenAiClient.Message("user", userMessage));

        if (conversationHistory.size() > MAX_HISTORY)
        {
            conversationHistory.subList(0, conversationHistory.size() - MAX_HISTORY).clear();
        }

        PlayerContext ctx = contextAssembler.assemble();
        String systemPrompt = PromptBuilder.buildSystemPrompt(ctx);

        String response = openAiClient.chatCompletion(systemPrompt, conversationHistory);

        conversationHistory.add(new OpenAiClient.Message("assistant", response));

        return response;
    }

    public void clearHistory()
    {
        conversationHistory.clear();
    }
}
```

- [ ] **Step 2: Implement AdviceService**

```java
package com.leaguesai.agent;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

@Slf4j
@Singleton
public class AdviceService
{
    private final OpenAiClient openAiClient;
    private final PlayerContextAssembler contextAssembler;

    @Inject
    public AdviceService(OpenAiClient openAiClient, PlayerContextAssembler contextAssembler)
    {
        this.openAiClient = openAiClient;
        this.contextAssembler = contextAssembler;
    }

    public String getAdvice() throws Exception
    {
        PlayerContext ctx = contextAssembler.assemble();
        String systemPrompt = PromptBuilder.buildSystemPrompt(ctx);
        String advicePrompt = PromptBuilder.buildAdvicePrompt();

        return openAiClient.chatCompletion(systemPrompt, List.of(
            new OpenAiClient.Message("user", advicePrompt)
        ));
    }
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/leaguesai/agent/ChatService.java src/main/java/com/leaguesai/agent/AdviceService.java
git commit -m "feat: add ChatService and AdviceService wiring agent to UI"
```

---

## Task 22: Wire Everything in LeaguesAiPlugin

**Files:**
- Modify: `src/main/java/com/leaguesai/LeaguesAiPlugin.java`
- Create: `src/main/resources/icon.png`

- [ ] **Step 1: Create a simple 16x16 icon.png**

Create a simple placeholder icon. Use any 16x16 PNG. The RuneLite sidebar needs it.

- [ ] **Step 2: Update LeaguesAiPlugin with full wiring**

```java
package com.leaguesai;

import com.google.inject.Provides;
import com.leaguesai.agent.*;
import com.leaguesai.core.events.*;
import com.leaguesai.core.monitors.*;
import com.leaguesai.data.*;
import com.leaguesai.overlay.*;
import com.leaguesai.ui.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.File;

@Slf4j
@PluginDescriptor(
    name = "Leagues AI",
    description = "AI-powered Leagues VI coach with goal planning, overlays, and chat",
    tags = {"leagues", "ai", "coach", "tasks", "overlay"}
)
public class LeaguesAiPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private LeaguesAiConfig config;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OverlayManager overlayManager;
    @Inject private EventBus eventBus;

    // Monitors
    @Inject private XpMonitor xpMonitor;
    @Inject private InventoryMonitor inventoryMonitor;
    @Inject private LocationMonitor locationMonitor;

    // Overlays
    @Inject private TileHighlightOverlay tileOverlay;
    @Inject private ArrowOverlay arrowOverlay;
    @Inject private NpcHighlightOverlay npcOverlay;
    @Inject private ObjectHighlightOverlay objectOverlay;
    @Inject private GroundItemOverlay groundItemOverlay;
    @Inject private MinimapOverlay minimapOverlay;
    @Inject private PathOverlay pathOverlay;
    @Inject private WidgetOverlay widgetOverlay;
    @Inject private OverlayController overlayController;

    // Agent
    @Inject private PlayerContextAssembler contextAssembler;

    private LeaguesAiPanel panel;
    private NavigationButton navButton;

    // Data
    private TaskRepositoryImpl taskRepo;
    private VectorIndex vectorIndex;
    private OpenAiClient openAiClient;
    private ChatService chatService;
    private AdviceService adviceService;

    @Override
    protected void startUp() throws Exception
    {
        log.info("Leagues AI plugin starting...");

        // Load database
        File dbFile = new File(System.getProperty("user.home"),
            ".runelite/leagues-ai/data/leagues-vi-tasks.db");
        DatabaseLoader dbLoader = new DatabaseLoader(dbFile);
        var tasks = dbLoader.loadTasks();
        var areas = dbLoader.loadAreas();
        var embeddings = dbLoader.loadEmbeddings();

        taskRepo = new TaskRepositoryImpl(tasks, areas);
        vectorIndex = new VectorIndex(embeddings);

        // OpenAI client
        String apiKey = config.openaiApiKey();
        openAiClient = new OpenAiClient(apiKey, config.openaiModel());

        chatService = new ChatService(openAiClient, contextAssembler, taskRepo, vectorIndex);
        adviceService = new AdviceService(openAiClient, contextAssembler);

        // Register monitors with eventbus
        eventBus.register(xpMonitor);
        eventBus.register(inventoryMonitor);
        eventBus.register(locationMonitor);

        // Register overlays
        overlayManager.add(tileOverlay);
        overlayManager.add(arrowOverlay);
        overlayManager.add(npcOverlay);
        overlayManager.add(objectOverlay);
        overlayManager.add(groundItemOverlay);
        overlayManager.add(minimapOverlay);
        overlayManager.add(pathOverlay);
        overlayManager.add(widgetOverlay);

        // UI Panel
        panel = new LeaguesAiPanel(config.animationSpeed());
        setupPanelCallbacks();

        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");
        navButton = NavigationButton.builder()
            .tooltip("Leagues AI")
            .icon(icon)
            .priority(5)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        log.info("Leagues AI plugin started. {} tasks loaded.", tasks.size());
    }

    @Override
    protected void shutDown() throws Exception
    {
        eventBus.unregister(xpMonitor);
        eventBus.unregister(inventoryMonitor);
        eventBus.unregister(locationMonitor);

        overlayManager.remove(tileOverlay);
        overlayManager.remove(arrowOverlay);
        overlayManager.remove(npcOverlay);
        overlayManager.remove(objectOverlay);
        overlayManager.remove(groundItemOverlay);
        overlayManager.remove(minimapOverlay);
        overlayManager.remove(pathOverlay);
        overlayManager.remove(widgetOverlay);

        overlayController.clearAll();
        clientToolbar.removeNavigation(navButton);

        log.info("Leagues AI plugin stopped");
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            xpMonitor.initialize();
        }
    }

    private void setupPanelCallbacks()
    {
        // Chat
        panel.getChatPanel().setOnSendMessage(message -> {
            new Thread(() -> {
                try
                {
                    String response = chatService.sendMessage(message);
                    panel.getChatPanel().appendMessage("AI", response);
                }
                catch (Exception e)
                {
                    panel.getChatPanel().appendMessage("Error", e.getMessage());
                    log.error("Chat error", e);
                }
            }).start();
        });

        // Advice
        panel.getAdvicePanel().setOnRefresh(() -> {
            new Thread(() -> {
                try
                {
                    String advice = adviceService.getAdvice();
                    panel.getAdvicePanel().setAdvice(advice);
                }
                catch (Exception e)
                {
                    panel.getAdvicePanel().setAdvice("Error: " + e.getMessage());
                    log.error("Advice error", e);
                }
            }).start();
        });

        // Settings - Goal
        panel.getSettingsPanel().setOnGoalSet(goal -> {
            contextAssembler.setCurrentGoal(goal);
            panel.getAdvicePanel().setGoal(goal);
            log.info("Goal set: {}", goal);
        });
    }

    @Provides
    LeaguesAiConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LeaguesAiConfig.class);
    }
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/leaguesai/LeaguesAiPlugin.java src/main/resources/icon.png
git commit -m "feat: wire all modules together in LeaguesAiPlugin"
```

---

## Task 23: Scraper Tool

**Files:**
- Create: `scraper/build.gradle`
- Create: `scraper/src/main/java/com/leaguesai/scraper/WikiScraper.java`
- Create: `scraper/src/main/java/com/leaguesai/scraper/HtmlParser.java`
- Create: `scraper/src/main/java/com/leaguesai/scraper/TaskNormalizer.java`
- Create: `scraper/src/main/java/com/leaguesai/scraper/LocationResolver.java`
- Create: `scraper/src/main/java/com/leaguesai/scraper/EmbeddingGenerator.java`
- Create: `scraper/src/main/java/com/leaguesai/scraper/SqliteWriter.java`
- Create: `scraper/scrape.sh`
- Create: `scraper/src/test/java/com/leaguesai/scraper/HtmlParserTest.java`

- [ ] **Step 1: Create scraper/build.gradle**

```gradle
plugins {
    id 'java'
    id 'application'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.jsoup:jsoup:1.17.2'
    implementation 'org.xerial:sqlite-jdbc:3.45.1.0'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.google.code.gson:gson:2.10.1'

    testImplementation 'junit:junit:4.13.2'
}

application {
    mainClass = 'com.leaguesai.scraper.WikiScraper'
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.release.set(11)
}
```

- [ ] **Step 2: Write failing HtmlParser test**

```java
package com.leaguesai.scraper;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class HtmlParserTest
{
    @Test
    public void testParseTaskTable()
    {
        String html = "<table class=\"wikitable\">"
            + "<tr><th>Task</th><th>Description</th><th>Difficulty</th><th>Points</th></tr>"
            + "<tr><td>Catch a Shrimp</td><td>Catch a shrimp in Lumbridge</td>"
            + "<td>Easy</td><td>10</td></tr>"
            + "</table>";

        List<Map<String, String>> rows = HtmlParser.parseTaskTable(html);

        assertEquals(1, rows.size());
        assertEquals("Catch a Shrimp", rows.get(0).get("Task"));
        assertEquals("Easy", rows.get(0).get("Difficulty"));
        assertEquals("10", rows.get(0).get("Points"));
    }

    @Test
    public void testParseEmptyTable()
    {
        String html = "<table class=\"wikitable\">"
            + "<tr><th>Task</th></tr></table>";

        List<Map<String, String>> rows = HtmlParser.parseTaskTable(html);
        assertTrue(rows.isEmpty());
    }
}
```

- [ ] **Step 3: Implement HtmlParser**

```java
package com.leaguesai.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HtmlParser
{
    public static List<Map<String, String>> parseTaskTable(String html)
    {
        Document doc = Jsoup.parse(html);
        Elements tables = doc.select("table.wikitable");
        List<Map<String, String>> results = new ArrayList<>();

        for (Element table : tables)
        {
            Elements headerCells = table.select("tr:first-child th");
            List<String> headers = new ArrayList<>();
            for (Element th : headerCells)
            {
                headers.add(th.text().trim());
            }

            Elements rows = table.select("tr:gt(0)");
            for (Element row : rows)
            {
                Elements cells = row.select("td");
                if (cells.size() < headers.size()) continue;

                Map<String, String> rowData = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++)
                {
                    rowData.put(headers.get(i), cells.get(i).text().trim());
                }
                results.add(rowData);
            }
        }

        return results;
    }
}
```

- [ ] **Step 4: Run HtmlParser test**

Run: `cd scraper && ../gradlew test --tests com.leaguesai.scraper.HtmlParserTest`
Expected: PASS

- [ ] **Step 5: Implement remaining scraper classes**

**TaskNormalizer.java:**
```java
package com.leaguesai.scraper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TaskNormalizer
{
    private static final Pattern SKILL_REQ = Pattern.compile("(\\d+)\\s+(\\w+)");

    public static Map<String, Integer> parseSkillRequirements(String reqText)
    {
        Map<String, Integer> skills = new LinkedHashMap<>();
        if (reqText == null || reqText.isEmpty()) return skills;

        Matcher m = SKILL_REQ.matcher(reqText);
        while (m.find())
        {
            int level = Integer.parseInt(m.group(1));
            String skill = m.group(2).toLowerCase();
            skills.put(skill, level);
        }
        return skills;
    }

    public static String normalizeDifficulty(String raw)
    {
        if (raw == null) return "easy";
        switch (raw.toLowerCase().trim())
        {
            case "easy": return "easy";
            case "medium": return "medium";
            case "hard": return "hard";
            case "elite": return "elite";
            case "master": return "master";
            default: return "easy";
        }
    }
}
```

**LocationResolver.java:**
```java
package com.leaguesai.scraper;

import java.util.HashMap;
import java.util.Map;

public class LocationResolver
{
    private static final Map<String, int[]> KNOWN_LOCATIONS = new HashMap<>();

    static
    {
        // Lumbridge
        KNOWN_LOCATIONS.put("lumbridge", new int[]{3222, 3218, 0});
        KNOWN_LOCATIONS.put("lumbridge swamp", new int[]{3199, 3169, 0});
        KNOWN_LOCATIONS.put("lumbridge fishing", new int[]{3243, 3152, 0});
        KNOWN_LOCATIONS.put("lumbridge range", new int[]{3231, 3196, 0});

        // Varrock
        KNOWN_LOCATIONS.put("varrock", new int[]{3213, 3428, 0});
        KNOWN_LOCATIONS.put("varrock bank", new int[]{3185, 3436, 0});

        // Falador
        KNOWN_LOCATIONS.put("falador", new int[]{2964, 3378, 0});

        // Catherby
        KNOWN_LOCATIONS.put("catherby", new int[]{2805, 3435, 0});
        KNOWN_LOCATIONS.put("catherby fishing", new int[]{2837, 3431, 0});

        // Karamja
        KNOWN_LOCATIONS.put("karamja", new int[]{2924, 3178, 0});
        KNOWN_LOCATIONS.put("brimhaven", new int[]{2771, 3179, 0});
    }

    public static int[] resolve(String description)
    {
        if (description == null) return null;
        String lower = description.toLowerCase();

        for (Map.Entry<String, int[]> entry : KNOWN_LOCATIONS.entrySet())
        {
            if (lower.contains(entry.getKey()))
            {
                return entry.getValue();
            }
        }

        return null;
    }
}
```

**EmbeddingGenerator.java:**
```java
package com.leaguesai.scraper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class EmbeddingGenerator
{
    private static final MediaType JSON = MediaType.parse("application/json");
    private final OkHttpClient client = new OkHttpClient();
    private final String apiKey;
    private final Gson gson = new Gson();

    public EmbeddingGenerator(String apiKey)
    {
        this.apiKey = apiKey;
    }

    public byte[] generate(String text) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("model", "text-embedding-3-small");
        body.addProperty("input", text);

        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/embeddings")
            .header("Authorization", "Bearer " + apiKey)
            .post(RequestBody.create(gson.toJson(body), JSON))
            .build();

        try (Response response = client.newCall(request).execute())
        {
            String respBody = response.body().string();
            JsonObject json = gson.fromJson(respBody, JsonObject.class);
            var arr = json.getAsJsonArray("data")
                .get(0).getAsJsonObject()
                .getAsJsonArray("embedding");

            ByteBuffer bb = ByteBuffer.allocate(arr.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < arr.size(); i++)
            {
                bb.putFloat(arr.get(i).getAsFloat());
            }
            return bb.array();
        }
    }
}
```

**SqliteWriter.java:**
```java
package com.leaguesai.scraper;

import com.google.gson.Gson;

import java.io.File;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SqliteWriter
{
    private final File dbFile;
    private final Gson gson = new Gson();

    public SqliteWriter(File dbFile)
    {
        this.dbFile = dbFile;
    }

    public void initialize() throws SQLException
    {
        try (Connection conn = connect(); Statement stmt = conn.createStatement())
        {
            stmt.execute("CREATE TABLE IF NOT EXISTS tasks ("
                + "id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT, "
                + "difficulty TEXT, points INTEGER, area TEXT, category TEXT, "
                + "skills_required TEXT, quests_required TEXT, tasks_required TEXT, "
                + "items_required TEXT, location TEXT, target_npcs TEXT, "
                + "target_objects TEXT, target_items TEXT, wiki_url TEXT, "
                + "embedding BLOB)");
            stmt.execute("CREATE TABLE IF NOT EXISTS areas ("
                + "id TEXT PRIMARY KEY, name TEXT NOT NULL, unlock_cost INTEGER, "
                + "unlock_requires TEXT, region_ids TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS relics ("
                + "id TEXT PRIMARY KEY, name TEXT, tier INTEGER, "
                + "description TEXT, unlock_cost INTEGER, effects TEXT)");
        }
    }

    public void upsertTask(String name, String description, String difficulty,
                           int points, String area, Map<String, Integer> skillsReq,
                           int[] location, byte[] embedding, String wikiUrl) throws SQLException
    {
        String id = UUID.nameUUIDFromBytes(name.getBytes()).toString();
        String locationJson = location != null
            ? String.format("{\"x\":%d,\"y\":%d,\"plane\":%d}", location[0], location[1], location[2])
            : null;

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR REPLACE INTO tasks (id, name, description, difficulty, points, area, "
                 + "skills_required, location, wiki_url, embedding) VALUES (?,?,?,?,?,?,?,?,?,?)"))
        {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setString(4, difficulty);
            ps.setInt(5, points);
            ps.setString(6, area);
            ps.setString(7, gson.toJson(skillsReq));
            ps.setString(8, locationJson);
            ps.setString(9, wikiUrl);
            ps.setBytes(10, embedding);
            ps.executeUpdate();
        }
    }

    private Connection connect() throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }
}
```

**WikiScraper.java (main entry point):**
```java
package com.leaguesai.scraper;

import org.jsoup.Jsoup;

import java.io.File;
import java.util.List;
import java.util.Map;

public class WikiScraper
{
    private static final String WIKI_BASE = "https://oldschool.runescape.wiki/w/";

    public static void main(String[] args) throws Exception
    {
        if (args.length < 2)
        {
            System.out.println("Usage: WikiScraper <openai-api-key> <output-db-path>");
            System.out.println("Example: WikiScraper sk-xxx ~/.runelite/leagues-ai/data/leagues-vi-tasks.db");
            System.exit(1);
        }

        String apiKey = args[0];
        File outputDb = new File(args[1]);
        outputDb.getParentFile().mkdirs();

        EmbeddingGenerator embedder = new EmbeddingGenerator(apiKey);
        SqliteWriter writer = new SqliteWriter(outputDb);
        writer.initialize();

        // Example: scrape Trailblazer Reloaded tasks
        // Replace this URL with the Leagues VI task page when available
        String[] taskPages = {
            "Trailblazer_Reloaded_League/Tasks/Misthalin",
            "Trailblazer_Reloaded_League/Tasks/Karamja",
            "Trailblazer_Reloaded_League/Tasks/Asgarnia",
        };

        for (String page : taskPages)
        {
            String area = page.substring(page.lastIndexOf('/') + 1).toLowerCase();
            System.out.println("Scraping: " + page + " (area: " + area + ")");

            String html = Jsoup.connect(WIKI_BASE + page)
                .userAgent("LeaguesAI-Scraper/1.0")
                .get()
                .html();

            List<Map<String, String>> rows = HtmlParser.parseTaskTable(html);
            System.out.println("  Found " + rows.size() + " tasks");

            for (Map<String, String> row : rows)
            {
                String name = row.getOrDefault("Task", "");
                String desc = row.getOrDefault("Description", "");
                String diff = TaskNormalizer.normalizeDifficulty(row.get("Difficulty"));
                int points = 0;
                try { points = Integer.parseInt(row.getOrDefault("Points", "0").replaceAll("[^0-9]", "")); }
                catch (NumberFormatException ignored) {}

                Map<String, Integer> skillsReq = TaskNormalizer.parseSkillRequirements(
                    row.getOrDefault("Requirements", ""));
                int[] location = LocationResolver.resolve(desc);

                byte[] embedding = null;
                try
                {
                    embedding = embedder.generate(name + " " + desc);
                    Thread.sleep(100); // rate limit
                }
                catch (Exception e)
                {
                    System.err.println("  Embedding failed for: " + name + " - " + e.getMessage());
                }

                writer.upsertTask(name, desc, diff, points, area, skillsReq,
                    location, embedding, WIKI_BASE + "Special:Search?search=" + name.replace(" ", "+"));
            }
        }

        System.out.println("Done! Database written to: " + outputDb.getAbsolutePath());
    }
}
```

- [ ] **Step 6: Create scrape.sh**

```bash
#!/bin/bash
# Usage: ./scrape.sh <openai-api-key>
# Outputs to ~/.runelite/leagues-ai/data/leagues-vi-tasks.db

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
API_KEY="${1:?Usage: ./scrape.sh <openai-api-key>}"
OUTPUT="$HOME/.runelite/leagues-ai/data/leagues-vi-tasks.db"

mkdir -p "$(dirname "$OUTPUT")"

cd "$SCRIPT_DIR"
../gradlew :scraper:run --args="$API_KEY $OUTPUT"

echo "Database written to: $OUTPUT"
```

Make executable: `chmod +x scraper/scrape.sh`

- [ ] **Step 7: Run scraper tests**

Run: `./gradlew :scraper:test`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add scraper/
git commit -m "feat: add standalone wiki scraper tool with HTML parser and SQLite writer"
```

---

## Task 24: Run Full Test Suite

- [ ] **Step 1: Run all tests**

Run: `./gradlew test`
Expected: All tests PASS

- [ ] **Step 2: Fix any compilation or test failures**

Address any issues found in the full test run.

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "chore: fix any remaining compilation issues from full build"
```

---

## Summary

**22 tasks** covering all 6 modules:

| Module | Tasks | Tests |
|--------|-------|-------|
| Scaffolding | 1 | Plugin launcher |
| Data (models, DB, repo, vectors) | 2-5 | DatabaseLoaderTest, TaskRepositoryImplTest, VectorIndexTest |
| Core (events, monitors) | 6-7 | XpMonitorTest, LocationMonitorTest |
| Agent (context, OpenAI, prompts, planner) | 8-11 | OpenAiClientTest, PromptBuilderTest, GoalPlannerTest, PlannerOptimizerTest |
| Overlay (9 types + controller, TDD) | 12-18 | 10 test classes, 100% coverage target |
| UI (sprites, panels) | 19-20 | SpriteAnimationTest, AnimationResolverTest |
| Services (chat, advice) | 21 | Integration via OpenAiClientTest |
| Integration | 22 | Full plugin wiring |
| Scraper | 23 | HtmlParserTest |
| Verification | 24 | Full test suite |
