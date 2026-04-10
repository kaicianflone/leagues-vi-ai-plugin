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
    @Inject private ClientToolbar clientToolbar;
    @Inject private OverlayManager overlayManager;
    @Inject private EventBus eventBus;

    @Inject private XpMonitor xpMonitor;
    @Inject private InventoryMonitor inventoryMonitor;
    @Inject private LocationMonitor locationMonitor;

    @Inject private TileHighlightOverlay tileOverlay;
    @Inject private ArrowOverlay arrowOverlay;
    @Inject private NpcHighlightOverlay npcOverlay;
    @Inject private ObjectHighlightOverlay objectOverlay;
    @Inject private GroundItemOverlay groundItemOverlay;
    @Inject private MinimapOverlay minimapOverlay;
    @Inject private PathOverlay pathOverlay;
    @Inject private WidgetOverlay widgetOverlay;
    @Inject private RequiredItemsOverlay requiredItemsOverlay;
    @Inject private OverlayController overlayController;

    @Inject private PlayerContextAssembler contextAssembler;

    // Constructed in loadDatabaseAsync() once TaskRepositoryImpl exists.
    private volatile GoalPlanner goalPlanner;

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

        // Initialise the database seeder (seeds on first run before loading)
        databaseSeeder = new DatabaseSeeder();

        // Async load the database so the game thread is not blocked
        llmExecutor.submit(this::loadDatabaseAsync);

        // Wire panel callbacks (services may still be null until load finishes — guarded inside)
        setupPanelCallbacks();

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
            vectorIndex = new VectorIndex(embeddings);
            goalPlanner = new GoalPlanner(taskRepo);

            // Gear repository and build system
            File buildsFile = new File(System.getProperty("user.home"),
                ".runelite/leagues-ai/data/builds.json");
            gearRepository = new GearRepository(dbFile);
            buildStore = new BuildStore(buildsFile);
            buildExpander = new BuildExpander(gearRepository, taskRepo, goalPlanner);
            proximityOptimizer = new ProximityOptimizer();

            String apiKey = config.openaiApiKey();
            LlmClient previous = openAiClient;

            // Decide auth mode: prefer Codex OAuth if ~/.codex/auth.json exists.
            LlmClient newClient;
            boolean codexMode = false;
            try {
                if (CodexAuthStore.hasValidAuth()) {
                    log.info("Using ChatGPT OAuth auth (found ~/.codex/auth.json)");
                    newClient = new CodexOauthClient("gpt-5-codex");
                    codexMode = true;
                } else {
                    if (apiKey == null || apiKey.isEmpty()) {
                        log.warn("No OpenAI API key and no Codex auth — chat will not work");
                    }
                    log.info("Using OpenAI API key auth");
                    newClient = new OpenAiClient(apiKey, config.openaiModel());
                }
            } catch (Exception e) {
                log.error("Failed to initialize LLM client", e);
                newClient = new OpenAiClient(apiKey == null ? "" : apiKey, config.openaiModel());
            }

            openAiClient = newClient;
            usingCodexOauth = codexMode;
            currentApiKey = apiKey != null ? apiKey : "";
            chatService = new ChatService(openAiClient, contextAssembler, taskRepo, vectorIndex, goalPlanner);
            chatService.setProximityOptimizer(proximityOptimizer);
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
                panel.getGoalsPanel().setUnlockablesPanel(unlock);
                // Restore any goals queued in a prior session
                refreshGoalQueueBar();
            });

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
                    LlmClient newClient = new CodexOauthClient("gpt-5-codex");
                    openAiClient = newClient;
                    if (taskRepo != null && vectorIndex != null) {
                        chatService = new ChatService(newClient, contextAssembler, taskRepo, vectorIndex, goalPlanner);
                        chatService.setProximityOptimizer(proximityOptimizer);
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
     * Fire the planner for all currently staged goals (gear + relics + areas + pacts).
     * Sends a natural-language message to ChatService so the LLM generates a real plan
     * and it appears in chat + GoalsPanel. Called on llmExecutor.
     */
    private void planAllStagedGoals() {
        if (goalStore == null || chatService == null) return;
        int total = goalStore.getTotalGoalCount();
        if (total == 0) {
            SwingUtilities.invokeLater(() -> panel.setStatus("No goals staged yet."));
            return;
        }

        StringBuilder sb = new StringBuilder("plan ");
        java.util.List<String> parts = new java.util.ArrayList<>();

        if (gearRepository != null && !goalStore.getGearGoals().isEmpty()) {
            java.util.List<String> gearNames = new java.util.ArrayList<>();
            for (String id : goalStore.getGearGoals()) {
                com.leaguesai.data.model.GearItem g = gearRepository.findById(id);
                gearNames.add(g != null ? g.getName() : id);
            }
            parts.add("get gear: " + String.join(", ", gearNames));
        }
        for (String id : goalStore.getRelicGoals()) parts.add("unlock relic " + id);
        for (String id : goalStore.getAreaGoals()) parts.add("unlock area " + id);
        for (String id : goalStore.getPactGoals()) parts.add("unlock pact " + id);
        sb.append(String.join("; ", parts));
        sb.append(" — give me an ordered task list for Leagues VI Demonic Pacts");

        String planPhrase = sb.toString();
        log.info("planAllStagedGoals: sending '{}' to ChatService", planPhrase);
        try {
            String response = chatService.sendMessage(planPhrase);
            if (response != null && !response.isEmpty()) {
                SwingUtilities.invokeLater(() -> {
                    panel.switchToGoalsTab();
                });
            }
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
        svc.setOnPlanCreated((goal, steps, review) -> {
            if (panel == null || steps == null || steps.isEmpty()) return;
            final int total = steps.size();
            final String safeGoal = goal == null ? "" : goal;

            // Goals panel: goal title, progress, review banner, accordion
            panel.getGoalsPanel().setGoal(safeGoal);
            panel.getGoalsPanel().setProgress(0, total);
            panel.getGoalsPanel().setReviewBanner(review);
            panel.getGoalsPanel().setSteps(steps);

            SwingUtilities.invokeLater(() -> {
                panel.setStatus("Plan: " + (safeGoal.length() > 30 ? safeGoal.substring(0, 27) + "..." : safeGoal));
                panel.setProgress(0, total);
            });

            // Overlay controller: activate the first step so arrows/highlights appear in-game
            PlannedStep first = steps.get(0);
            if (first != null && overlayController != null) {
                SwingUtilities.invokeLater(() -> overlayController.setActiveStep(first));
                log.info("Plan callback: activating first step '{}' — location={}, npcIds={}, objIds={}",
                        first.getInstruction(),
                        first.getDestination(),
                        first.getOverlayData() != null ? first.getOverlayData().getTargetNpcIds() : "none",
                        first.getOverlayData() != null ? first.getOverlayData().getTargetObjectIds() : "none");
            }

            long withLocation = steps.stream().filter(s -> s.getDestination() != null).count();
            log.info("Plan callback: pushed {} steps to GoalsPanel and overlays ({} have a resolved location, review={})",
                    total, withLocation, review != null);
        });
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

            List<PlannedStep> steps = optimized.stream()
                    .map(t -> {
                        List<Integer> npcIds = new ArrayList<>();
                        if (t.getTargetNpcs() != null) t.getTargetNpcs().forEach(n -> npcIds.add(n.getId()));
                        List<Integer> objIds = new ArrayList<>();
                        if (t.getTargetObjects() != null) t.getTargetObjects().forEach(o -> objIds.add(o.getId()));
                        List<Integer> itemIds = new ArrayList<>();
                        if (t.getTargetItems() != null) t.getTargetItems().forEach(i -> itemIds.add(i.getId()));
                        com.leaguesai.overlay.OverlayData overlayData =
                                com.leaguesai.overlay.OverlayData.builder()
                                        .targetTile(t.getLocation())
                                        .targetNpcIds(npcIds)
                                        .targetObjectIds(objIds)
                                        .targetItemIds(itemIds)
                                        .pathPoints(Collections.emptyList())
                                        .widgetIds(Collections.emptyList())
                                        .showArrow(t.getLocation() != null)
                                        .showMinimap(t.getLocation() != null)
                                        .showWorldMap(t.getLocation() != null)
                                        .build();
                        return PlannedStep.builder()
                                .task(t)
                                .destination(t.getLocation())
                                .instruction(t.getName())
                                .overlayData(overlayData)
                                .build();
                    })
                    .collect(Collectors.toList());

            // Step 3b: Relic-aware proximity reorder (post-PlannedStep pass)
            if (proximityOptimizer != null && !steps.isEmpty()) {
                steps = proximityOptimizer.optimize(steps, ctx, ctx.getUnlockedAreas());
            }

            // Step 4: Now persist (after successful expansion)
            if (goalStore != null) {
                goalStore.unionBuildPicks(build);
            }

            if (!steps.isEmpty()) {
                // Step 5a: Full plan path — mirror exactly what attachPlanCallback does
                contextAssembler.setCurrentGoal(build.getName());
                contextAssembler.setCurrentPlan(steps);

                final List<PlannedStep> finalSteps = steps;
                final String buildName = build.getName();
                final int total = steps.size();

                if (panel != null) {
                    panel.getGoalsPanel().setGoal(buildName);
                    panel.getGoalsPanel().setProgress(0, total);
                    panel.getGoalsPanel().setReviewBanner(null);
                    panel.getGoalsPanel().setSteps(finalSteps);
                }
                SwingUtilities.invokeLater(() -> {
                    if (panel != null) {
                        panel.setStatus("Build: " + buildName);
                        panel.setProgress(0, total);
                        panel.switchToGoalsTab();
                    }
                    PlannedStep first = finalSteps.get(0);
                    if (first != null && overlayController != null) {
                        overlayController.setActiveStep(first);
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
                panel.getGoalsPanel(),
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
                openAiClient = new OpenAiClient(safeKey, config.openaiModel());
                if (taskRepo != null && vectorIndex != null) {
                    chatService = new ChatService(openAiClient, contextAssembler, taskRepo, vectorIndex, goalPlanner);
                    chatService.setProximityOptimizer(proximityOptimizer);
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

        // Wire BuildsPanel callbacks
        if (panel.getBuildsPanel() != null) {
            BuildsPanel bp = panel.getBuildsPanel();

            bp.setOnBackToGoals(() -> panel.switchToGoalsTab());

            bp.setOnActivate(build -> {
                // Called on background thread from BuildsPanel's internal executor
                boolean ok = activateBuild(build);
                SwingUtilities.invokeLater(() -> {
                    if (ok) bp.showToast("Build activated.");
                    if (buildStore != null) bp.refreshBuilds(buildStore);
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
        panel.getGoalsPanel().setOnSaveGoalsAsBuild(this::saveGoalsAsBuild);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            xpMonitor.initialize();
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

        // Tear down the OkHttp client AFTER the executor is gone so no in-flight
        // requests are using it.
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
