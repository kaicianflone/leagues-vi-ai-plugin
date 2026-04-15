package com.leaguesai;

import com.google.inject.Provides;
import com.leaguesai.agent.*;
import com.leaguesai.core.monitors.*;
import com.leaguesai.data.*;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Build;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;
import com.leaguesai.overlay.*;
import com.leaguesai.ui.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
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
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@PluginDescriptor(
    name = "Leagues AI",
    description = "AI-powered Leagues VI coach with goal planning, overlays, and chat",
    tags = {"leagues", "ai", "coach", "tasks", "overlay"}
)
public class LeaguesAiPlugin extends Plugin {

    @Inject private Client client;
    @Inject private LeaguesAiConfig config;
    @Inject private ConfigManager configManager;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OverlayManager overlayManager;
    @Inject private EventBus eventBus;

    @Inject private XpMonitor xpMonitor;
    @Inject private InventoryMonitor inventoryMonitor;
    @Inject private LocationMonitor locationMonitor;
    @Inject private LeagueStatusMonitor leagueStatusMonitor;
    @Inject private WikiSyncTaskLoader wikiSyncTaskLoader;

    @Inject private TileHighlightOverlay tileOverlay;
    @Inject private ArrowOverlay arrowOverlay;
    @Inject private NpcHighlightOverlay npcOverlay;
    @Inject private ObjectHighlightOverlay objectOverlay;
    @Inject private GroundItemOverlay groundItemOverlay;
    @Inject private MinimapOverlay minimapOverlay;
    @Inject private PathOverlay pathOverlay;
    @Inject private WidgetOverlay widgetOverlay;
    @Inject private RequiredItemsOverlay requiredItemsOverlay;
    @Inject private StepInstructionOverlay stepInstructionOverlay;
    @Inject private OverlayController overlayController;

    @Inject private PlayerContextAssembler contextAssembler;

    // Constructed in loadDatabaseAsync() once TaskRepositoryImpl exists.
    private volatile GoalPlanner goalPlanner;
    private volatile com.leaguesai.agent.WikiItemLookup wikiItemLookup;

    private volatile DatabaseSeeder databaseSeeder;

    private LeaguesAiPanel panel;
    private NavigationButton navButton;
    private ExecutorService llmExecutor;

    // Services constructed at runtime (need config/loaded data)
    private volatile TaskRepositoryImpl taskRepo;
    private volatile GoalStore goalStore;
    private volatile VectorIndex vectorIndex;
    private volatile LlmClient openAiClient;
    private volatile ChatService chatService;
    private volatile CoachPulseService coachPulseService;
    private volatile com.leaguesai.ui.HeartbeatTicker heartbeatTicker;
    private volatile GearRepository gearRepository;
    private volatile BuildStore buildStore;
    private volatile BuildExpander buildExpander;
    private volatile ProximityOptimizer proximityOptimizer;
    private volatile ChatHistoryStore chatHistoryStore;
    private volatile ItemDependencyGraph itemDependencyGraph;
    private volatile UserPreferences userPreferences;
    private volatile UnlockablesPanel unlockablesPanel;

    /**
     * Active plan steps + current index for inventory-driven auto-advance.
     * {@code activePlanSteps} is written from the llmExecutor thread (plan callback)
     * and read from the game thread ({@code onInventoryStateEvent}). The index is
     * written by the game thread only. Both are {@code volatile} for cross-thread
     * visibility. The index is always written to 0 <em>before</em> {@code activePlanSteps}
     * is set to the new list, so a concurrent game-thread read never sees a stale
     * index from a previous plan paired with the new step list.
     */
    private volatile List<com.leaguesai.agent.PlannedStep> activePlanSteps = null;
    private volatile int activePlanStepIndex = 0;

    /** Preferences file — model + persona selection persisted across sessions. */
    private static final File PREFS_FILE = new File(System.getProperty("user.home"),
            ".runelite/leagues-ai/preferences.json");

    /**
     * Set to true at the very top of {@link #shutDown()}. Any in-flight
     * background task that wants to rebuild services / start the heartbeat
     * checks this and bails so we never resurrect a torn-down ticker.
     */
    private volatile boolean shuttingDown = false;

    // Tracks the API key the current OpenAiClient was built with so we only
    // rebuild (and tear down OkHttp resources) on actual change.
    private volatile String currentApiKey = "";

    // True when the active LlmClient is a CodexOauthClient (ChatGPT OAuth mode).
    // In that case, the API key field is ignored — we authenticate via
    // ~/.codex/auth.json instead.
    private volatile boolean usingCodexOauth = false;

    private volatile long loginWatchStartTime = 0;

    @Override
    protected void startUp() throws Exception {
        log.info("Leagues AI starting...");

        // Re-initialise the executor so re-enable after shutDown() works.
        llmExecutor = Executors.newSingleThreadExecutor();

        // Create panel immediately so the sidebar is available while data loads
        panel = new LeaguesAiPanel(config.animationSpeed());
        panel.getSettingsPanel().setDatabaseStatus("Loading task database...", false);

        final BufferedImage icon = ImageUtil.loadImageResource(LeaguesAiPlugin.class, "icon.png");
        navButton = NavigationButton.builder()
            .tooltip("Leagues AI")
            .icon(icon)
            .priority(5)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        // Register event monitors
        eventBus.register(xpMonitor);
        eventBus.register(inventoryMonitor);
        eventBus.register(locationMonitor);
        eventBus.register(leagueStatusMonitor);

        // Overlays that maintain caches via spawn/despawn events must be on the bus
        eventBus.register(objectOverlay);
        eventBus.register(groundItemOverlay);
        // ArrowOverlay needs GameTick for its blink cadence (ported from Quest Helper)
        eventBus.register(arrowOverlay);

        // Register overlays
        overlayManager.add(tileOverlay);
        overlayManager.add(arrowOverlay);
        overlayManager.add(npcOverlay);
        overlayManager.add(objectOverlay);
        overlayManager.add(groundItemOverlay);
        overlayManager.add(minimapOverlay);
        overlayManager.add(pathOverlay);
        overlayManager.add(widgetOverlay);
        overlayManager.add(requiredItemsOverlay);
        overlayManager.add(stepInstructionOverlay);

        // Initialise the database seeder (seeds on first run before loading)
        databaseSeeder = new DatabaseSeeder();

        // Wire panel callbacks BEFORE submitting async load. This guarantees that
        // restoreSavedSession (called at the end of loadDatabaseAsync) fires after
        // all plan/session callbacks are already registered, eliminating a race
        // where a fast-loading DB could invoke SwingUtilities.invokeLater before
        // the listeners are wired.
        setupPanelCallbacks();

        // Async load the database so the game thread is not blocked
        llmExecutor.submit(this::loadDatabaseAsync);

        // Wire sign-in button (legacy settings panel button — still works as fallback)
        panel.getSettingsPanel().setOnSignIn(this::launchCodexLogin);

        // Wire pre-auth sign-in button
        panel.setOnPreAuthSignIn(this::launchCodexLogin);
    }

    private void loadDatabaseAsync() {
        try {
            File dbFile = new File(System.getProperty("user.home"),
                ".runelite/leagues-ai/data/leagues-vi-tasks.db");
            databaseSeeder.seedIfAbsent(dbFile);
            DatabaseLoader loader = new DatabaseLoader(dbFile);
            List<Task> tasks = loader.loadTasks();
            List<Area> areas = loader.loadAreas();
            List<Relic> relics = loader.loadRelics();
            List<Pact> pacts = loader.loadPacts();
            Map<String, float[]> embeddings = loader.loadEmbeddings();

            taskRepo = new TaskRepositoryImpl(tasks, areas, relics, pacts);
            File goalsFile = new File(System.getProperty("user.home"),
                ".runelite/leagues-ai/data/goals.json");
            goalStore = new GoalStore(goalsFile);
            leagueStatusMonitor.setGoalStore(goalStore);
            wikiSyncTaskLoader.setContextAssembler(contextAssembler);
            wikiSyncTaskLoader.setTaskRepository(taskRepo);
            wikiSyncTaskLoader.loadCompletedTasks(); // fetch once DB is ready
            vectorIndex = new VectorIndex(embeddings);
            ItemDependencyGraph itemDependencyGraph = new ItemDependencyGraph(dbFile);
            itemDependencyGraph.loadFromDb();
            this.itemDependencyGraph = itemDependencyGraph;
            goalPlanner = new GoalPlanner(taskRepo, itemDependencyGraph);
            wikiItemLookup = new com.leaguesai.agent.WikiItemLookup();
            goalPlanner.setWikiItemLookup(wikiItemLookup);

            // Gear repository and build system
            File buildsFile = new File(System.getProperty("user.home"),
                ".runelite/leagues-ai/data/builds.json");
            gearRepository = new GearRepository(dbFile);
            buildStore = new BuildStore(buildsFile);
            buildExpander = new BuildExpander(gearRepository, taskRepo, goalPlanner);
            proximityOptimizer = new ProximityOptimizer();

            // Load user preferences (model + persona selection)
            UserPreferences prefs = UserPreferences.load(PREFS_FILE);
            userPreferences = prefs;
            String selectedModel = prefs.getModel();
            log.info("User preferences loaded — model: {}, personas: {}",
                    selectedModel, prefs.getSelectedPersonas());

            // Push prefs to settings panel so checkboxes + dropdown reflect saved state
            SwingUtilities.invokeLater(() -> {
                panel.getSettingsPanel().setSelectedModel(selectedModel);
                panel.getSettingsPanel().setSelectedPersonas(prefs.getSelectedPersonas());
            });

            String apiKey = config.openaiApiKey();
            LlmClient previous = openAiClient;

            // Decide auth mode: prefer Codex OAuth if ~/.codex/auth.json exists.
            LlmClient newClient;
            boolean codexMode = false;
            try {
                if (CodexAuthStore.hasValidAuth()) {
                    log.info("Using ChatGPT OAuth auth (found ~/.codex/auth.json), model: {}", selectedModel);
                    newClient = new CodexOauthClient(selectedModel);
                    codexMode = true;
                } else {
                    if (apiKey == null || apiKey.isEmpty()) {
                        log.warn("No OpenAI API key and no Codex auth — chat will not work");
                    }
                    log.info("Using OpenAI API key auth, model: {}", selectedModel);
                    newClient = new OpenAiClient(apiKey, selectedModel);
                }
            } catch (Exception e) {
                log.error("Failed to initialize LLM client", e);
                newClient = new OpenAiClient(apiKey == null ? "" : apiKey, selectedModel);
            }

            openAiClient = newClient;
            usingCodexOauth = codexMode;
            currentApiKey = apiKey != null ? apiKey : "";
            chatService = new ChatService(openAiClient, contextAssembler, taskRepo, vectorIndex, goalPlanner, config);
            chatService.setItemDependencyGraph(itemDependencyGraph);
            chatService.setProximityOptimizer(proximityOptimizer);
            chatService.setUserPreferences(prefs);
            File chatHistoryFile = new File(System.getProperty("user.home"),
                ".runelite/leagues-ai/data/chat-history.json");
            chatHistoryStore = new ChatHistoryStore(chatHistoryFile);
            chatService.setHistoryStore(chatHistoryStore);
            attachPlanCallback(chatService);
            coachPulseService = new CoachPulseService(openAiClient, contextAssembler);
            // (Re)build the heartbeat ticker so it points at the new client.
            rebuildHeartbeatTicker();
            if (previous != null) {
                previous.close();
            }

            final boolean codexModeFinal = codexMode;
            SwingUtilities.invokeLater(() -> {
                panel.getSettingsPanel().setAuthMode(
                        codexModeFinal
                                ? "Auth: ChatGPT OAuth (from ~/.codex/auth.json)"
                                : "Auth: API Key");
                panel.setAuthenticated(codexModeFinal);
                if (!codexModeFinal) {
                    panel.setPreAuthButtonText("Sign in with ChatGPT");
                    panel.setPreAuthButtonEnabled(true);
                }
            });

            log.info("Leagues AI: loaded {} tasks, {} areas, {} relics, {} pacts",
                    tasks.size(), areas.size(), relics.size(), pacts.size());

            // Build + mount the unlockables goal picker now that repo is ready.
            final GoalStore gs = goalStore;
            SwingUtilities.invokeLater(() -> {
                UnlockablesPanel unlock = new UnlockablesPanel(taskRepo, gs);
                if (gearRepository != null) unlock.setGearRepository(gearRepository);
                unlock.setOnSetGoal(this::handleUnlockableGoalClick);
                unlock.setOnSetGearGoal(item -> stageGearGoal(item));
                unlock.setLeaguesMode(config.leaguesMode());
                unlockablesPanel = unlock;
                panel.getGoalsPanel().setUnlockablesPanel(unlock);
                panel.getGoalsPanel().setTaskRepository(taskRepo);
                // Restore any goals queued in a prior session
                refreshGoalQueueBar();
            });

            // Restore previous session (chat history + active plan) now that all
            // services are live. Must run before the UI update so the panel is
            // populated by the time it becomes visible.
            restoreSavedSession();

            SwingUtilities.invokeLater(() -> {
                if (tasks.isEmpty()) {
                    panel.getSettingsPanel().setDatabaseStatus(
                        "Database: not found (run scraper)", true);
                } else {
                    panel.getSettingsPanel().setDatabaseStatus(
                        "Database: " + tasks.size() + " tasks loaded", false);
                }
                if (!codexModeFinal && (apiKey == null || apiKey.isEmpty())) {
                    panel.getChatPanel().showError("No API key set. Add it in Settings.");
                }
            });
        } catch (Exception e) {
            log.error("Failed to load database", e);
            SwingUtilities.invokeLater(() ->
                panel.getSettingsPanel().setDatabaseStatus(
                    "Error loading: " + e.getMessage(), true));
        }
    }

    private void launchCodexLogin() {
        llmExecutor.submit(() -> {
            try {
                // Capture current auth.json state
                File authFile = new File(System.getProperty("user.home"), ".codex/auth.json");
                long beforeMod = authFile.exists() ? authFile.lastModified() : 0L;

                // Spawn codex login in a new Terminal window
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("mac")) {
                    pb = new ProcessBuilder("osascript", "-e",
                        "tell application \"Terminal\" to do script \"codex login\"");
                } else if (os.contains("linux")) {
                    pb = new ProcessBuilder("x-terminal-emulator", "-e", "codex login");
                } else {
                    pb = new ProcessBuilder("cmd", "/c", "start", "cmd", "/k", "codex login");
                }
                pb.start();

                // Wait for auth.json to update (up to 2 minutes)
                loginWatchStartTime = System.currentTimeMillis();
                boolean success = false;
                while (System.currentTimeMillis() - loginWatchStartTime < 120_000) {
                    Thread.sleep(2000);
                    if (authFile.exists() && authFile.lastModified() > beforeMod) {
                        success = true;
                        break;
                    }
                }

                if (success) {
                    log.info("Codex login detected, rebuilding LLM client");
                    LlmClient old = openAiClient;
                    String model = userPreferences != null ? userPreferences.getModel() : UserPreferences.DEFAULT_MODEL;
                    LlmClient newClient = new CodexOauthClient(model);
                    openAiClient = newClient;
                    if (taskRepo != null && vectorIndex != null) {
                        chatService = new ChatService(newClient, contextAssembler, taskRepo, vectorIndex, goalPlanner, config);
                        chatService.setItemDependencyGraph(this.itemDependencyGraph);
                        chatService.setProximityOptimizer(proximityOptimizer);
                        chatService.setHistoryStore(chatHistoryStore);
                        chatService.setUserPreferences(userPreferences);
                        attachPlanCallback(chatService);
                        coachPulseService = new CoachPulseService(newClient, contextAssembler);
                        rebuildHeartbeatTicker();
                    }
                    usingCodexOauth = true;
                    if (old != null) {
                        try { old.close(); } catch (Exception ignored) {}
                    }
                    SwingUtilities.invokeLater(() -> {
                        panel.getSettingsPanel().setAuthMode("Auth: ChatGPT OAuth (from ~/.codex/auth.json)");
                        panel.getSettingsPanel().setSignInButtonText("Re-authenticate with ChatGPT");
                        panel.getSettingsPanel().setSignInButtonEnabled(true);
                        panel.setAuthenticated(true);
                        panel.setPreAuthButtonText("Sign in with ChatGPT");
                        panel.setPreAuthButtonEnabled(true);
                        panel.getChatPanel().appendMessage("System", "Signed in successfully.");
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        panel.getSettingsPanel().setSignInButtonText("Sign in with ChatGPT");
                        panel.getSettingsPanel().setSignInButtonEnabled(true);
                        panel.setPreAuthButtonText("Sign in with ChatGPT");
                        panel.setPreAuthButtonEnabled(true);
                        panel.getChatPanel().showError("Login timed out after 2 minutes. Check if Terminal opened and whether you completed the browser flow.");
                    });
                }
            } catch (Exception e) {
                log.error("Failed to launch Codex login", e);
                SwingUtilities.invokeLater(() -> {
                    panel.getSettingsPanel().setSignInButtonText("Sign in with ChatGPT");
                    panel.getSettingsPanel().setSignInButtonEnabled(true);
                    panel.setPreAuthButtonText("Sign in with ChatGPT");
                    panel.setPreAuthButtonEnabled(true);
                    panel.getChatPanel().showError("Failed to launch Codex login: " + e.getMessage());
                });
            }
        });
    }

    /**
     * Handle a "Set as goal" click from the UnlockablesPanel. The panel
     * hands us a planner-ready phrase starting with "plan ..."; we send it
     * through {@code chatService.sendMessage} on the LLM executor so the
     * planner fires and the chat panel shows the response. Runs async so
     * the click doesn't block the EDT.
     */
    private void handleUnlockableGoalClick(String goalPhrase) {
        if (goalPhrase == null || goalPhrase.isEmpty()) return;
        if (chatService == null) {
            SwingUtilities.invokeLater(() ->
                panel.getChatPanel().showError("Database not loaded yet. Wait a moment."));
            return;
        }
        SwingUtilities.invokeLater(() -> {
            panel.getChatPanel().appendMessage("You", goalPhrase);
            panel.getChatPanel().setLoading(true);
            panel.switchToChatTab();
        });
        llmExecutor.submit(() -> {
            try {
                String response = chatService.sendMessage(goalPhrase);
                SwingUtilities.invokeLater(() -> {
                    panel.getChatPanel().appendMessage("AI", response);
                    panel.getChatPanel().setLoading(false);
                });
            } catch (Exception e) {
                log.error("Unlockable goal chat error", e);
                SwingUtilities.invokeLater(() -> {
                    panel.getChatPanel().showError(e.getMessage());
                    panel.getChatPanel().setLoading(false);
                });
            }
        });
    }

    /**
     * Snapshot the current GoalStore picks into a new named Build and persist it.
     * Called from BuildsPanel's "Save current as build" button.
     */
    private void saveCurrentAsBuild(String name) {
        if (buildStore == null || goalStore == null) {
            SwingUtilities.invokeLater(() -> {
                if (panel.getBuildsPanel() != null) {
                    panel.getBuildsPanel().showToast("Not ready — try again in a moment.");
                }
            });
            return;
        }
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "_");
        // Make the slug unique if a build with that id already exists.
        boolean idTaken = buildStore.listAll().stream().anyMatch(b -> slug.equals(b.getId()));
        String finalSlug = idTaken ? slug + "_" + System.currentTimeMillis() : slug;
        com.leaguesai.data.model.Build build = com.leaguesai.data.model.Build.builder()
                .id(finalSlug)
                .name(name)
                .author(System.getProperty("user.name", "me"))
                .version(1)
                .relicIds(new java.util.HashSet<>(goalStore.getRelicGoals()))
                .areaIds(new java.util.HashSet<>(goalStore.getAreaGoals()))
                .pactIds(new java.util.HashSet<>(goalStore.getPactGoals()))
                .build();
        try {
            buildStore.save(build);
            log.info("Saved build '{}' (id={})", name, finalSlug);
            SwingUtilities.invokeLater(() -> {
                if (panel.getBuildsPanel() != null) {
                    panel.getBuildsPanel().refreshBuilds(buildStore);
                    panel.getBuildsPanel().showToast("Build \"" + name + "\" saved.");
                }
            });
        } catch (Exception ex) {
            log.error("Failed to save build '{}'", name, ex);
            SwingUtilities.invokeLater(() -> {
                if (panel.getBuildsPanel() != null) {
                    panel.getBuildsPanel().showToast("Save failed: " + ex.getMessage());
                }
            });
        }
    }

    /**
     * Stage a gear item as a goal. Does NOT trigger planning — the player can
     * accumulate multiple goals across gear/relics/areas/pacts, then hit
     * "Plan goals" in the Goals tab to generate a combined plan.
     * Called on EDT or llmExecutor (safe either way).
     */
    private void stageGearGoal(com.leaguesai.data.model.GearItem item) {
        if (item == null || goalStore == null) return;
        boolean added = !goalStore.isGearGoal(item.getId());
        goalStore.addGearGoal(item.getId());
        refreshGoalQueueBar();
        if (added) {
            SwingUtilities.invokeLater(() -> {
                panel.switchToGoalsTab();
                panel.setStatus("Added: " + item.getName());
            });
        }
    }

    /** Push current GoalStore goal counts + names into the GoalsPanel queue bar. */
    private void refreshGoalQueueBar() {
        if (panel == null || goalStore == null) return;
        int total = goalStore.getTotalGoalCount();
        java.util.List<String> names = new java.util.ArrayList<>();
        if (gearRepository != null) {
            for (String id : goalStore.getGearGoals()) {
                com.leaguesai.data.model.GearItem g = gearRepository.findById(id);
                if (g != null) names.add(g.getName());
                else names.add(id);
            }
        }
        for (String id : goalStore.getRelicGoals()) names.add(id);
        for (String id : goalStore.getAreaGoals()) names.add(id);
        panel.getGoalsPanel().updateGoalQueue(total, names);
    }

    /**
     * Resolve all staged goals directly through the planner and push the combined
     * plan into the Goals panel + overlays. Called on llmExecutor.
     *
     * <p>Each staged goal is resolved to a {@link GoalSpec} individually so the
     * planner can handle the correct goal type (ITEM / RELIC / AREA / PACT). Tasks
     * from all goals are combined, deduplicated, topologically sorted, and passed
     * to {@link #activatePlan} — the same path that the ChatService plan callback
     * uses, so the UI update is identical.
     */
    private void planAllStagedGoals() {
        if (goalStore == null || goalPlanner == null) return;
        if (chatService == null) {
            SwingUtilities.invokeLater(() -> panel.setStatus("Not ready yet — wait a moment."));
            return;
        }
        int total = goalStore.getTotalGoalCount();
        if (total == 0) {
            SwingUtilities.invokeLater(() -> panel.setStatus("No goals staged yet."));
            return;
        }

        try {
            PlayerContext ctx = contextAssembler.assemble();
            java.util.List<Task> allTasks = new java.util.ArrayList<>();
            java.util.Set<String> seenIds = new java.util.LinkedHashSet<>();
            java.util.List<String> goalNames = new java.util.ArrayList<>();

            // --- Gear goals → GoalType.ITEM ---
            for (String gearId : goalStore.getGearGoals()) {
                com.leaguesai.data.model.GearItem g = gearRepository != null ? gearRepository.findById(gearId) : null;
                String name = g != null ? g.getName() : gearId;
                goalNames.add(name);

                // Try item dependency graph first (gives a proper prereq chain)
                if (itemDependencyGraph != null && !itemDependencyGraph.isEmpty()) {
                    com.leaguesai.data.model.ItemDependency found =
                            itemDependencyGraph.findLongestMatchingItem(name.toLowerCase());
                    if (found != null) {
                        GoalSpec spec = GoalSpec.builder()
                                .type(GoalType.ITEM)
                                .targetId(found.getItemId())
                                .targetName(found.getItemName())
                                .rawPhrase("get " + name)
                                .unlockCost(0)
                                .build();
                        try {
                            CompositeGoal cg = goalPlanner.resolveCompositeGoal(spec, ctx);
                            for (Task t : cg.getTaskBatch()) {
                                if (t != null && t.getId() != null && seenIds.add(t.getId())) allTasks.add(t);
                            }
                            continue;
                        } catch (Exception ex) {
                            log.warn("planAllStagedGoals: item graph resolution failed for '{}': {}", name, ex.getMessage());
                        }
                    }
                }
                // Fallback: keyword task search for this individual item name
                try {
                    java.util.List<Task> found = goalPlanner.resolveGoalTasks("get " + name);
                    for (Task t : found) {
                        if (t != null && t.getId() != null && seenIds.add(t.getId())) allTasks.add(t);
                    }
                } catch (Exception ex) {
                    log.warn("planAllStagedGoals: keyword resolution failed for '{}': {}", name, ex.getMessage());
                }
            }

            // --- Relic goals → GoalType.RELIC ---
            for (String id : goalStore.getRelicGoals()) {
                String name = id;
                int cost = 0;
                if (taskRepo != null) {
                    for (Relic r : taskRepo.getAllRelics()) {
                        if (r != null && id.equals(r.getId())) { name = r.getName(); cost = r.getUnlockCost(); break; }
                    }
                }
                goalNames.add(name);
                GoalSpec spec = GoalSpec.builder()
                        .type(GoalType.RELIC)
                        .targetId(id)
                        .targetName(name)
                        .rawPhrase("unlock relic " + name)
                        .unlockCost(cost)
                        .build();
                try {
                    CompositeGoal cg = goalPlanner.resolveCompositeGoal(spec, ctx);
                    for (Task t : cg.getTaskBatch()) {
                        if (t != null && t.getId() != null && seenIds.add(t.getId())) allTasks.add(t);
                    }
                } catch (Exception ex) {
                    log.warn("planAllStagedGoals: relic resolution failed for '{}': {}", id, ex.getMessage());
                }
            }

            // --- Area goals → GoalType.AREA ---
            for (String id : goalStore.getAreaGoals()) {
                String name = id;
                int cost = 0;
                if (taskRepo != null) {
                    for (Area a : taskRepo.getAllAreas()) {
                        if (a != null && id.equals(a.getId())) { name = a.getName(); cost = a.getUnlockCost(); break; }
                    }
                }
                goalNames.add(name);
                GoalSpec spec = GoalSpec.builder()
                        .type(GoalType.AREA)
                        .targetId(id)
                        .targetName(name)
                        .rawPhrase("unlock " + name)
                        .unlockCost(cost)
                        .build();
                try {
                    CompositeGoal cg = goalPlanner.resolveCompositeGoal(spec, ctx);
                    for (Task t : cg.getTaskBatch()) {
                        if (t != null && t.getId() != null && seenIds.add(t.getId())) allTasks.add(t);
                    }
                } catch (Exception ex) {
                    log.warn("planAllStagedGoals: area resolution failed for '{}': {}", id, ex.getMessage());
                }
            }

            // --- Pact goals — no tasks to plan, just record the name ---
            for (String id : goalStore.getPactGoals()) {
                goalNames.add(id);
            }

            if (allTasks.isEmpty()) {
                String msg = goalStore.getPactGoals().isEmpty()
                        ? "No tasks found. Run the scraper and try again."
                        : "Pact goals set — no tasks to plan.";
                SwingUtilities.invokeLater(() -> panel.setStatus(msg));
                log.info("planAllStagedGoals: no tasks resolved for {} staged goals", total);
                return;
            }

            // Build DAG, topological sort, proximity optimise
            java.util.Set<String> completed = new java.util.HashSet<>();
            java.util.List<Task> dag = goalPlanner.buildDag(allTasks, completed);
            java.util.List<Task> sorted;
            try {
                sorted = goalPlanner.topologicalSort(dag);
            } catch (IllegalStateException cycle) {
                log.warn("planAllStagedGoals: cycle in DAG, using insertion order: {}", cycle.getMessage());
                sorted = dag;
            }

            net.runelite.api.coords.WorldPoint loc = ctx.getLocation();
            java.util.List<Task> optimized = PlannerOptimizer.optimizeOrder(sorted, loc);
            java.util.List<PlannedStep> steps = ChatService.buildSteps(optimized);
            if (proximityOptimizer != null && !steps.isEmpty()) {
                java.util.Set<String> unlockedAreas = ctx.getUnlockedAreas();
                steps = proximityOptimizer.optimize(steps, ctx, unlockedAreas);
            }

            // Build a human-readable goal label
            int showNames = Math.min(goalNames.size(), 3);
            String goalLabel = String.join(", ", goalNames.subList(0, showNames));
            if (goalNames.size() > showNames) goalLabel += " +" + (goalNames.size() - showNames) + " more";

            log.info("planAllStagedGoals: resolved {} tasks for {} goals ({})",
                    steps.size(), goalNames.size(), goalLabel);

            final java.util.List<PlannedStep> finalSteps = steps;
            final String finalLabel = goalLabel;
            activatePlan(finalLabel, finalSteps, null);
            SwingUtilities.invokeLater(panel::switchToGoalsTab);

        } catch (Exception ex) {
            log.error("planAllStagedGoals failed: {}", ex.getMessage(), ex);
            SwingUtilities.invokeLater(() -> panel.setStatus("Plan failed: " + ex.getMessage()));
        }
    }

    /**
     * Save all currently staged goals (gear + relics + areas + pacts) as a named build.
     * Shows an input dialog for the build name, then persists to BuildStore.
     */
    private void saveGoalsAsBuild() {
        SwingUtilities.invokeLater(() -> {
            if (goalStore == null || buildStore == null) return;
            String name = javax.swing.JOptionPane.showInputDialog(
                    panel, "Build name:", "Save goals as build", javax.swing.JOptionPane.PLAIN_MESSAGE);
            if (name == null || name.trim().isEmpty()) return;
            name = name.trim();

            // Resolve gear goals to a slot map
            java.util.Map<com.leaguesai.data.model.GearSlot, String> gear = new java.util.LinkedHashMap<>();
            if (gearRepository != null) {
                for (String id : goalStore.getGearGoals()) {
                    com.leaguesai.data.model.GearItem g = gearRepository.findById(id);
                    if (g != null && g.getSlot() != null) gear.put(g.getSlot(), g.getId());
                }
            }

            String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "_");
            boolean idTaken = buildStore.listAll().stream().anyMatch(b -> slug.equals(b.getId()));
            String finalSlug = idTaken ? slug + "_" + System.currentTimeMillis() : slug;

            com.leaguesai.data.model.Build build = com.leaguesai.data.model.Build.builder()
                    .id(finalSlug).name(name)
                    .author(System.getProperty("user.name", "me")).version(1)
                    .gear(gear)
                    .relicIds(new java.util.HashSet<>(goalStore.getRelicGoals()))
                    .areaIds(new java.util.HashSet<>(goalStore.getAreaGoals()))
                    .pactIds(new java.util.HashSet<>(goalStore.getPactGoals()))
                    .build();

            try {
                buildStore.save(build);
                if (panel.getBuildsPanel() != null) {
                    panel.getBuildsPanel().refreshBuilds(buildStore);
                    panel.getBuildsPanel().showToast("Build \"" + name + "\" saved.");
                }
                panel.setStatus("Saved build: " + name);
            } catch (Exception ex) {
                log.error("saveGoalsAsBuild failed: {}", ex.getMessage(), ex);
                panel.setStatus("Save failed: " + ex.getMessage());
            }
        });
    }

    /**
     * Attach the plan-created callback to a freshly-built ChatService so that
     * a successful planner run updates the goals panel (accordion + review
     * banner) and the overlay controller. The chat panel intentionally does
     * NOT receive the plan — chat is chat-only now.
     */
    private void attachPlanCallback(ChatService svc) {
        if (svc == null) return;
        svc.setOnPlanCreated((goal, steps, review) -> activatePlan(goal, steps, review));
    }

    /**
     * Push a resolved plan into the UI and overlay controller.
     * Called both from the ChatService plan callback and directly from
     * {@link #planAllStagedGoals()} so both code paths share identical behaviour.
     */
    private void activatePlan(String goal, List<PlannedStep> steps, String review) {
        if (panel == null || steps == null || steps.isEmpty()) return;
        final int total = steps.size();
        final String safeGoal = goal == null ? "" : goal;

        // Persist plan so it survives a RuneLite restart.
        if (goalStore != null) {
            List<String> taskIds = steps.stream()
                    .filter(s -> s.getTask() != null && s.getTask().getId() != null)
                    .map(s -> s.getTask().getId())
                    .collect(Collectors.toList());
            goalStore.saveCurrentPlan(safeGoal, taskIds);
        }

        // Goals panel: goal title, progress, review banner, accordion
        panel.getGoalsPanel().setGoal(safeGoal);
        panel.getGoalsPanel().setProgress(0, total);
        panel.getGoalsPanel().setReviewBanner(review);
        panel.getGoalsPanel().setSteps(steps);

        SwingUtilities.invokeLater(() -> {
            panel.setStatus("Plan: " + (safeGoal.length() > 30 ? safeGoal.substring(0, 27) + "..." : safeGoal));
            panel.setProgress(0, total);
        });

        // Store plan for auto-advance and activate first step.
        // Write index BEFORE steps: onInventoryStateEvent reads both on the client thread.
        // If steps were written first a concurrent inventory event could read the new
        // steps with a stale (non-zero) index and skip straight to the wrong step.
        activePlanStepIndex = 0;
        activePlanSteps = steps;

        PlannedStep first = steps.get(0);
        if (first != null && overlayController != null) {
            overlayController.setPlanSize(total);
            SwingUtilities.invokeLater(() -> overlayController.setActiveStep(first, 0));
            log.info("Plan callback: activating first step '{}' — location={}, npcIds={}, objIds={}",
                    first.getInstruction(),
                    first.getDestination(),
                    first.getOverlayData() != null ? first.getOverlayData().getTargetNpcIds() : "none",
                    first.getOverlayData() != null ? first.getOverlayData().getTargetObjectIds() : "none");
        }

        long withLocation = steps.stream().filter(s -> s.getDestination() != null).count();
        log.info("Plan callback: pushed {} steps to GoalsPanel and overlays ({} have a resolved location, review={})",
                total, withLocation, review != null);
    }

    /**
     * Restore the last active plan and chat history from disk after the DB and
     * all services are fully loaded. Called at the end of {@link #loadDatabaseAsync}.
     *
     * <p>Chat history: pre-populates {@code ChatService}'s in-memory list and
     * replays messages into the ChatPanel so the visual history is visible.
     *
     * <p>Plan: resolves persisted task IDs back to {@link Task} objects, builds
     * {@link PlannedStep}s, and pushes them to the GoalsPanel + OverlayController
     * exactly as if the plan had just been created.
     */
    private void restoreSavedSession() {
        // Restore chat history
        if (chatHistoryStore != null && chatService != null) {
            java.util.List<ChatHistoryStore.Entry> history = chatHistoryStore.load();
            if (!history.isEmpty()) {
                chatService.loadHistory(history);
                SwingUtilities.invokeLater(() -> {
                    if (panel == null) return;
                    for (ChatHistoryStore.Entry e : history) {
                        String sender = "user".equals(e.role) ? "You" : "AI";
                        panel.getChatPanel().appendMessage(sender, e.content);
                    }
                });
                log.info("Restored {} chat history entries", history.size());
            }
        }

        // Restore active plan
        if (goalStore == null || taskRepo == null) return;
        String goalText = goalStore.getCurrentGoalText();
        java.util.List<String> taskIds = goalStore.getCurrentPlanTaskIds();
        if (goalText == null || taskIds == null || taskIds.isEmpty()) return;

        java.util.List<Task> tasks = taskIds.stream()
                .map(taskRepo::getById)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        if (tasks.isEmpty()) {
            // Stale IDs (e.g. DB was regenerated with new IDs) — clear to avoid
            // a phantom plan showing on every restart.
            goalStore.clearCurrentPlan();
            log.info("Restored plan task IDs were all stale — cleared saved plan");
            return;
        }

        java.util.List<PlannedStep> steps = ChatService.buildSteps(tasks);
        if (contextAssembler != null) {
            contextAssembler.setCurrentGoal(goalText);
            contextAssembler.setCurrentPlan(steps);
        }

        final java.util.List<PlannedStep> finalSteps = steps;
        final String finalGoal = goalText;
        SwingUtilities.invokeLater(() -> {
            if (panel != null) {
                panel.getGoalsPanel().setGoal(finalGoal);
                panel.getGoalsPanel().setProgress(0, finalSteps.size());
                panel.getGoalsPanel().setSteps(finalSteps);
                panel.switchToGoalsTab();
            }
            PlannedStep first = finalSteps.isEmpty() ? null : finalSteps.get(0);
            if (first != null && overlayController != null) {
                overlayController.setPlanSize(finalSteps.size());
                overlayController.setActiveStep(first, 0);
            }
        });
        log.info("Restored session plan: '{}' with {} steps", finalGoal, steps.size());
    }

    /**
     * Activate a gear build: expand into a task plan, persist goal picks, update UI.
     *
     * <p>Must be called on the llmExecutor thread (not EDT). The BuildsPanel
     * Activate button dispatches here via llmExecutor.submit().
     *
     * <p>Order of operations (critical — do NOT reorder):
     * <ol>
     *   <li>Cancel any in-flight chat plan (stale-plan race guard)</li>
     *   <li>Expand the build (read-only — no state mutation yet)</li>
     *   <li>If expansion throws, log + toast, leave GoalStore untouched</li>
     *   <li>Persist goal picks (GoalStore.unionBuildPicks)</li>
     *   <li>If steps non-empty: mirror attachPlanCallback body (panel + overlays)</li>
     *   <li>If steps empty (goals-only): update Goals panel directly with banner</li>
     * </ol>
     */
    public boolean activateBuild(Build build) {
        if (build == null) return false;
        if (buildExpander == null) {
            log.warn("activateBuild: buildExpander not ready (db still loading?), skipping");
            SwingUtilities.invokeLater(() -> {
                if (panel != null) panel.setStatus("Build not ready — database still loading.");
            });
            return false;
        }
        if (chatService != null) {
            chatService.cancelPendingPlan();
        }

        try {
            PlayerContext ctx = contextAssembler.assemble();

            // Step 2: Expand first (read-only — GoalStore not touched yet)
            CompositeGoal goal = buildExpander.expand(build, ctx);

            // Step 3: Build PlannedSteps (mirrors ChatService lines 283-316 — pure transform)
            List<Task> tasks = goal.getTaskBatch();
            net.runelite.api.coords.WorldPoint loc = ctx.getLocation();
            List<Task> optimized = PlannerOptimizer.optimizeOrder(
                    tasks != null ? tasks : Collections.emptyList(), loc);

            List<PlannedStep> steps = ChatService.buildSteps(optimized);

            // Step 3b: Relic-aware proximity reorder (post-PlannedStep pass)
            if (proximityOptimizer != null && !steps.isEmpty()) {
                steps = proximityOptimizer.optimize(steps, ctx, ctx.getUnlockedAreas());
            }

            // Step 4: Now persist (after successful expansion)
            if (goalStore != null) {
                goalStore.unionBuildPicks(build);
                if (!steps.isEmpty()) {
                    List<String> taskIds = steps.stream()
                            .filter(s -> s.getTask() != null && s.getTask().getId() != null)
                            .map(s -> s.getTask().getId())
                            .collect(Collectors.toList());
                    goalStore.saveCurrentPlan(build.getName(), taskIds);
                }
            }

            if (!steps.isEmpty()) {
                // Step 5a: Full plan path — mirror exactly what attachPlanCallback does
                contextAssembler.setCurrentGoal(build.getName());
                contextAssembler.setCurrentPlan(steps);

                final List<PlannedStep> finalSteps = steps;
                final String buildName = build.getName();
                final int total = steps.size();

                SwingUtilities.invokeLater(() -> {
                    if (panel != null) {
                        panel.getGoalsPanel().setGoal(buildName);
                        panel.getGoalsPanel().setProgress(0, total);
                        panel.getGoalsPanel().setReviewBanner(null);
                        panel.getGoalsPanel().setSteps(finalSteps);
                        panel.setStatus("Build: " + buildName);
                        panel.setProgress(0, total);
                        panel.switchToGoalsTab();
                    }
                    if (!finalSteps.isEmpty() && overlayController != null) {
                        overlayController.setPlanSize(total);
                        overlayController.setActiveStep(finalSteps.get(0), 0);
                    }
                });
                log.info("activateBuild: activated '{}' with {} steps", buildName, total);
            } else {
                // Step 5b: Goals-only path — direct panel update, NO onPlanCreated
                final String buildName = build.getName();
                SwingUtilities.invokeLater(() -> {
                    if (panel != null && panel.getGoalsPanel() != null) {
                        panel.getGoalsPanel().setGoal(buildName);
                        // Show a banner so user knows gear chain is pending
                        panel.getGoalsPanel().setReviewBanner(
                            "Goals set — gear task chain will activate on launch-day scrape");
                        panel.switchToGoalsTab();
                    }
                });
                log.info("activateBuild: goals-only mode for build '{}'", build.getName());
            }
            return true;

        } catch (Exception e) {
            log.error("activateBuild failed for build '{}': {}",
                      build.getName(), e.getMessage(), e);
            // Do NOT touch GoalStore — leave it untouched on error
            SwingUtilities.invokeLater(() -> {
                if (panel != null) {
                    panel.setStatus("Build activation failed: " + e.getMessage());
                }
            });
            return false;
        }
    }

    /**
     * (Re)build the heartbeat ticker so it picks up the freshly built coach
     * pulse service. Stops any prior ticker first. Safe to call repeatedly.
     */
    private void rebuildHeartbeatTicker() {
        if (panel == null) return;
        // Refuse to rebuild during teardown — otherwise an in-flight
        // background task can resurrect a ticker after shutDown() ran.
        if (shuttingDown) {
            log.info("rebuildHeartbeatTicker: skipped (plugin is shutting down)");
            return;
        }
        com.leaguesai.ui.HeartbeatTicker old = heartbeatTicker;
        if (old != null) {
            SwingUtilities.invokeLater(old::stop);
        }
        com.leaguesai.ui.HeartbeatTicker fresh = new com.leaguesai.ui.HeartbeatTicker(
                panel.getChatPanel(),
                new LocalHeartbeat(),
                coachPulseService,
                contextAssembler,
                llmExecutor
        );
        heartbeatTicker = fresh;
        // Re-check the flag inside the EDT lambda — shutDown may have flipped
        // it after we built `fresh` but before the EDT runs the start.
        SwingUtilities.invokeLater(() -> {
            if (shuttingDown) return;
            fresh.start();
        });
    }

    private void setupPanelCallbacks() {
        // Chat send
        panel.getChatPanel().setOnSendMessage(message -> {
            if (chatService == null) {
                panel.getChatPanel().showError("Database not loaded yet. Wait a moment.");
                return;
            }
            panel.getChatPanel().setLoading(true);
            llmExecutor.submit(() -> {
                try {
                    String response = chatService.sendMessage(message);
                    SwingUtilities.invokeLater(() -> {
                        panel.getChatPanel().appendMessage("AI", response);
                        panel.getChatPanel().setLoading(false);
                    });
                } catch (Exception e) {
                    log.error("Chat error", e);
                    SwingUtilities.invokeLater(() -> {
                        panel.getChatPanel().showError(e.getMessage());
                        panel.getChatPanel().setLoading(false);
                    });
                }
            });
        });

        // Clear chat — wipes LLM memory and persisted history
        panel.getChatPanel().setOnClear(() -> {
            ChatService svc = chatService;
            if (svc != null) svc.clearHistory();
        });

        // Cross-panel navigation links
        panel.getChatPanel().setOnOpenGoals(() -> panel.switchToGoalsTab());
        panel.getGoalsPanel().setOnOpenChat(() -> panel.switchToChatTab());

        // Settings — goal
        panel.getSettingsPanel().setOnGoalSet(goal -> {
            contextAssembler.setCurrentGoal(goal);
            panel.getGoalsPanel().setGoal(goal);
            log.info("Goal set: {}", goal);
        });

        // Settings — API key changed: rebuild the client and dependent services
        // ONLY if the key actually differs from what we already have, otherwise
        // every focus/blur leaks a fresh OkHttp dispatcher + connection pool.
        panel.getSettingsPanel().setOnApiKeyChanged(newKey -> {
            String safeKey = newKey != null ? newKey : "";
            if (usingCodexOauth) {
                SwingUtilities.invokeLater(() ->
                    panel.getSettingsPanel().setDatabaseStatus(
                        "Using ChatGPT OAuth — API key field ignored.", false));
                return;
            }
            if (safeKey.equals(currentApiKey)) {
                return; // no-op, key unchanged
            }
            currentApiKey = safeKey;
            llmExecutor.submit(() -> {
                LlmClient old = openAiClient;
                String model = userPreferences != null ? userPreferences.getModel() : UserPreferences.DEFAULT_MODEL;
                openAiClient = new OpenAiClient(safeKey, model);
                if (taskRepo != null && vectorIndex != null) {
                    chatService = new ChatService(openAiClient, contextAssembler, taskRepo, vectorIndex, goalPlanner, config);
                    chatService.setItemDependencyGraph(this.itemDependencyGraph);
                    chatService.setProximityOptimizer(proximityOptimizer);
                    chatService.setHistoryStore(chatHistoryStore);
                    chatService.setUserPreferences(userPreferences);
                    attachPlanCallback(chatService);
                    coachPulseService = new CoachPulseService(openAiClient, contextAssembler);
                    rebuildHeartbeatTicker();
                    log.info("OpenAI client rebuilt with new API key");
                    SwingUtilities.invokeLater(() ->
                        panel.getSettingsPanel().setDatabaseStatus(
                            "API key updated. Services restarted.", false));
                }
                if (old != null) {
                    old.close();
                }
            });
        });

        // Settings — refresh data
        panel.getSettingsPanel().setOnRefreshData(() -> {
            SwingUtilities.invokeLater(() ->
                panel.getSettingsPanel().setDatabaseStatus("Reloading...", false));
            llmExecutor.submit(this::loadDatabaseAsync);
        });

        // Settings — model changed: persist + rebuild LLM client
        panel.getSettingsPanel().setOnModelChanged(newModel -> {
            if (newModel == null || newModel.isEmpty()) return;
            llmExecutor.submit(() -> {
                UserPreferences prefs = userPreferences;
                if (prefs == null) prefs = new UserPreferences();
                prefs.setModel(newModel);
                prefs.save(PREFS_FILE);
                userPreferences = prefs;

                LlmClient old = openAiClient;
                LlmClient newClient;
                if (usingCodexOauth) {
                    newClient = new CodexOauthClient(newModel);
                } else {
                    newClient = new OpenAiClient(currentApiKey, newModel);
                }
                openAiClient = newClient;
                ChatService svc = chatService;
                if (svc != null) svc.setUserPreferences(userPreferences);
                // Rebuild ChatService with new client
                if (taskRepo != null && vectorIndex != null) {
                    ChatService newSvc = new ChatService(newClient, contextAssembler, taskRepo, vectorIndex, goalPlanner, config);
                    newSvc.setItemDependencyGraph(itemDependencyGraph);
                    newSvc.setProximityOptimizer(proximityOptimizer);
                    newSvc.setHistoryStore(chatHistoryStore);
                    newSvc.setUserPreferences(userPreferences);
                    attachPlanCallback(newSvc);
                    chatService = newSvc;
                }
                if (old != null) old.close();
                log.info("Model changed to: {}", newModel);
            });
        });

        // Settings — personas changed: persist + update active ChatService
        panel.getSettingsPanel().setOnPersonasChanged(personas -> {
            llmExecutor.submit(() -> {
                UserPreferences prefs = userPreferences;
                if (prefs == null) prefs = new UserPreferences();
                prefs.setSelectedPersonas(personas);
                prefs.save(PREFS_FILE);
                userPreferences = prefs;
                ChatService svc = chatService;
                if (svc != null) svc.setUserPreferences(userPreferences);
                log.info("Personas updated: {}", personas);
            });
        });

        // Wire BuildsPanel callbacks
        if (panel.getBuildsPanel() != null) {
            BuildsPanel bp = panel.getBuildsPanel();

            bp.setOnBackToGoals(() -> panel.switchToGoalsTab());

            bp.setOnActivate(build -> {
                // Route through llmExecutor so activateBuild serializes with in-flight
                // chat plans — both write to GoalStore/currentPlan and must not race.
                llmExecutor.submit(() -> {
                    boolean ok = activateBuild(build);
                    SwingUtilities.invokeLater(() -> {
                        if (ok) bp.showToast("Build activated.");
                        if (buildStore != null) bp.refreshBuilds(buildStore);
                    });
                });
            });

            bp.setOnExport(build -> {
                // Show JFileChooser on EDT
                SwingUtilities.invokeLater(() -> {
                    javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
                    fc.setSelectedFile(new File(build.getId() != null ? build.getId() : "build" + ".json"));
                    int result = fc.showSaveDialog(panel);
                    if (result == javax.swing.JFileChooser.APPROVE_OPTION && buildStore != null) {
                        try {
                            buildStore.exportToFile(build, fc.getSelectedFile());
                            bp.showToast("Saved to " + fc.getSelectedFile().getName());
                        } catch (Exception ex) {
                            bp.showToast("Export failed: " + ex.getMessage());
                        }
                    }
                });
            });

            bp.setOnImport(() -> {
                SwingUtilities.invokeLater(() -> {
                    javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
                    int result = fc.showOpenDialog(panel);
                    if (result == javax.swing.JFileChooser.APPROVE_OPTION && buildStore != null) {
                        try {
                            buildStore.importFromFile(fc.getSelectedFile());
                            bp.refreshBuilds(buildStore);
                            bp.showToast("Build imported.");
                        } catch (IllegalArgumentException ex) {
                            bp.showToast("Invalid build file: " + ex.getMessage());
                        } catch (Exception ex) {
                            bp.showToast("Import failed: " + ex.getMessage());
                        }
                    }
                });
            });

            bp.setOnSaveAsBuild(name -> saveCurrentAsBuild(name));

            // Initial load — buildStore may still be null here (loaded async);
            // refreshBuilds handles null gracefully.
            bp.refreshBuilds(buildStore);
        }

        // Wire Goals panel → Builds panel navigation
        panel.getGoalsPanel().setOnBrowseBuilds(() ->
                SwingUtilities.invokeLater(panel::switchToBuildsTab));

        // Wire goal queue bar actions
        panel.getGoalsPanel().setOnPlanGoals(() ->
                llmExecutor.submit(this::planAllStagedGoals));
        panel.getGoalsPanel().setOnClearGoals(() -> {
            if (goalStore != null) {
                goalStore.clearAllGoals();
                refreshGoalQueueBar();
            }
        });
        panel.getGoalsPanel().setOnSaveGoalsAsBuild(this::saveGoalsAsBuild);

        // Clear active plan + goal (separate from clearing staged goals queue)
        panel.getGoalsPanel().setOnClearPlan(() -> {
            if (goalStore != null) goalStore.clearCurrentPlan();
            if (contextAssembler != null) {
                contextAssembler.setCurrentGoal(null);
                contextAssembler.setCurrentPlan(java.util.Collections.emptyList());
            }
            SwingUtilities.invokeLater(() -> {
                panel.getGoalsPanel().setGoal(null);
                panel.getGoalsPanel().setSteps(java.util.Collections.emptyList());
                panel.getGoalsPanel().setReviewBanner(null);
                panel.getGoalsPanel().setProgress(0, 0);
            });
            overlayController.clearAll();
        });

        // Leagues mode toggle
        panel.getSettingsPanel().setOnLeaguesModeChanged(on -> {
            configManager.setConfiguration("leaguesai", "leaguesMode", on);
            if (!on) {
                if (goalStore != null) goalStore.clearAllGoals();
                overlayController.clearAll();
            }
            panel.setLeaguesMode(on);
            UnlockablesPanel up = unlockablesPanel;
            if (up != null) up.setLeaguesMode(on);
            log.info("Leagues mode: {}", on ? "LEAGUES" : "IRONMAN");
        });

        // Initialize mode badge + unlockables visibility from config
        boolean initialLeaguesMode = config.leaguesMode();
        panel.setLeaguesMode(initialLeaguesMode);
        panel.getSettingsPanel().setLeaguesMode(initialLeaguesMode);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            // Reset per-session state before re-loading so a logout/relog or
            // account switch doesn't carry over the previous session's completions.
            contextAssembler.reset();
            xpMonitor.initialize();
            leagueStatusMonitor.initialize();
            // Re-fetch completed tasks from WikiSync on each login so pre-existing
            // completions are always reflected without requiring an in-session completion.
            wikiSyncTaskLoader.loadCompletedTasks();
        }
    }

    /**
     * Detect league task completion via the in-game chat message broadcast.
     * Leagues VI sends: "You have completed the task: <name>." as a GAMEMESSAGE.
     * We look up the task by name and call markTaskCompleted so the next plan
     * automatically excludes it from the step list.
     */
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE) return;
        String msg = event.getMessage();
        if (msg == null) return;
        // Strip HTML colour tags RuneLite injects
        String clean = msg.replaceAll("<[^>]+>", "").trim();
        if (!clean.startsWith("You have completed the task:")) return;

        // Extract task name: "You have completed the task: <name>."
        int colon = clean.indexOf(':');
        if (colon < 0) return;
        String taskName = clean.substring(colon + 1).trim();
        if (taskName.endsWith(".")) taskName = taskName.substring(0, taskName.length() - 1).trim();

        log.info("LeaguesAI: task completion detected via chat — '{}'", taskName);

        if (taskRepo == null) return;
        final String finalTaskName = taskName;
        List<Task> all = taskRepo.getAllTasks();
        if (all == null) return;
        for (Task t : all) {
            if (finalTaskName.equalsIgnoreCase(t.getName())) {
                log.info("LeaguesAI: marking task completed — id={} name={}", t.getId(), t.getName());
                contextAssembler.markTaskCompleted(t.getId());
                break;
            }
        }
    }

    /**
     * Auto-advance the active plan step when the player's inventory satisfies the
     * current step's {@code completionItemIds}.
     *
     * <p>Only wiki-lookup steps (shop/bank) populate {@code completionItemIds}.
     * Task-based steps (Leagues tasks) don't use this mechanism.
     */
    @Subscribe
    public void onInventoryStateEvent(com.leaguesai.core.events.InventoryStateEvent event) {
        List<com.leaguesai.agent.PlannedStep> plan = activePlanSteps;
        if (plan == null || plan.isEmpty()) return;
        int idx = activePlanStepIndex;
        if (idx >= plan.size()) return;

        com.leaguesai.agent.PlannedStep currentStep = plan.get(idx);
        List<Integer> needed = currentStep.getCompletionItemIds();
        if (needed == null || needed.isEmpty()) return;

        java.util.Map<Integer, Integer> inv = event.getItems();
        java.util.Map<Integer, Integer> minQtys = currentStep.getCompletionItemMinQtys();
        boolean satisfied = needed.stream().anyMatch(id -> {
            if (id == null) return false;
            int held = inv.getOrDefault(id, 0);
            int required = (minQtys != null) ? minQtys.getOrDefault(id, 1) : 1;
            return held >= required;
        });
        if (!satisfied) return;

        int nextIdx = idx + 1;
        activePlanStepIndex = nextIdx;

        if (nextIdx < plan.size()) {
            com.leaguesai.agent.PlannedStep next = plan.get(nextIdx);
            log.info("Plan auto-advance: step {} → step {} ('{}')",
                    idx + 1, nextIdx + 1,
                    next.getInstruction() != null ? next.getInstruction() : "(no instruction)");
            SwingUtilities.invokeLater(() -> {
                if (overlayController != null) overlayController.setActiveStep(next, nextIdx);
                if (panel != null) panel.getGoalsPanel().setProgress(nextIdx, plan.size());
                if (panel != null) panel.setProgress(nextIdx, plan.size());
            });
        } else {
            log.info("Plan auto-advance: all {} steps complete — clearing overlays", plan.size());
            activePlanSteps = null;
            SwingUtilities.invokeLater(() -> {
                if (overlayController != null) overlayController.clearAll();
                if (panel != null) panel.getGoalsPanel().setProgress(plan.size(), plan.size());
                if (panel != null) panel.setProgress(plan.size(), plan.size());
            });
        }
    }

    @Override
    protected void shutDown() throws Exception {
        // Set this BEFORE doing anything else so any in-flight background
        // task that tries to rebuild services / start the heartbeat bails.
        shuttingDown = true;
        eventBus.unregister(xpMonitor);
        eventBus.unregister(inventoryMonitor);
        eventBus.unregister(locationMonitor);
        eventBus.unregister(leagueStatusMonitor);
        wikiSyncTaskLoader.shutdown();
        eventBus.unregister(objectOverlay);
        eventBus.unregister(groundItemOverlay);
        eventBus.unregister(arrowOverlay);

        overlayManager.remove(tileOverlay);
        overlayManager.remove(arrowOverlay);
        overlayManager.remove(npcOverlay);
        overlayManager.remove(objectOverlay);
        overlayManager.remove(groundItemOverlay);
        overlayManager.remove(minimapOverlay);
        overlayManager.remove(pathOverlay);
        overlayManager.remove(widgetOverlay);
        overlayManager.remove(requiredItemsOverlay);
        overlayManager.remove(stepInstructionOverlay);

        overlayController.clearAll();

        // Stop the heartbeat ticker before tearing down the executor it uses.
        // invokeAndWait throws IllegalStateException (an Error subclass parent
        // is Throwable, but it's RuntimeException-class) if called from the EDT,
        // so guard with isEventDispatchThread().
        if (heartbeatTicker != null) {
            final com.leaguesai.ui.HeartbeatTicker ticker = heartbeatTicker;
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    ticker.stop();
                } else {
                    SwingUtilities.invokeAndWait(ticker::stop);
                }
            } catch (Throwable t) {
                log.warn("HeartbeatTicker shutdown failed: {}", t.getMessage());
            }
            heartbeatTicker = null;
        }

        // Stop the sprite animation timer before disposing the panel.
        if (panel != null && panel.getSpriteRenderer() != null) {
            panel.getSpriteRenderer().dispose();
        }

        clientToolbar.removeNavigation(navButton);

        if (panel != null && panel.getBuildsPanel() != null) {
            panel.getBuildsPanel().shutdown();
        }

        if (llmExecutor != null) {
            llmExecutor.shutdown();
            if (!llmExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                llmExecutor.shutdownNow();
            }
            llmExecutor = null;
        }

        // Tear down OkHttp clients AFTER the executor is gone so no in-flight
        // requests are using them.
        if (wikiItemLookup != null) {
            wikiItemLookup.close();
            wikiItemLookup = null;
        }
        if (openAiClient != null) {
            openAiClient.close();
            openAiClient = null;
        }

        log.info("Leagues AI stopped");
    }

    @Provides
    LeaguesAiConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(LeaguesAiConfig.class);
    }
}
