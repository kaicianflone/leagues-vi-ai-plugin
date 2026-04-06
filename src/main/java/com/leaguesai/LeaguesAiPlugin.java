package com.leaguesai;

import com.google.inject.Provides;
import com.leaguesai.agent.*;
import com.leaguesai.core.monitors.*;
import com.leaguesai.data.*;
import com.leaguesai.data.model.Area;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    @Inject private OverlayController overlayController;

    @Inject private PlayerContextAssembler contextAssembler;

    private LeaguesAiPanel panel;
    private NavigationButton navButton;
    private ExecutorService llmExecutor;

    // Services constructed at runtime (need config/loaded data)
    private volatile TaskRepositoryImpl taskRepo;
    private volatile VectorIndex vectorIndex;
    private volatile LlmClient openAiClient;
    private volatile ChatService chatService;
    private volatile AdviceService adviceService;

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

        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");
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

        // Register overlays
        overlayManager.add(tileOverlay);
        overlayManager.add(arrowOverlay);
        overlayManager.add(npcOverlay);
        overlayManager.add(objectOverlay);
        overlayManager.add(groundItemOverlay);
        overlayManager.add(minimapOverlay);
        overlayManager.add(pathOverlay);
        overlayManager.add(widgetOverlay);

        // Async load the database so the game thread is not blocked
        llmExecutor.submit(this::loadDatabaseAsync);

        // Wire panel callbacks (services may still be null until load finishes — guarded inside)
        setupPanelCallbacks();

        // Wire sign-in button
        panel.getSettingsPanel().setOnSignIn(this::launchCodexLogin);
    }

    private void loadDatabaseAsync() {
        try {
            File dbFile = new File(System.getProperty("user.home"),
                ".runelite/leagues-ai/data/leagues-vi-tasks.db");
            DatabaseLoader loader = new DatabaseLoader(dbFile);
            List<Task> tasks = loader.loadTasks();
            List<Area> areas = loader.loadAreas();
            Map<String, float[]> embeddings = loader.loadEmbeddings();

            taskRepo = new TaskRepositoryImpl(tasks, areas);
            vectorIndex = new VectorIndex(embeddings);

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
            chatService = new ChatService(openAiClient, contextAssembler, taskRepo, vectorIndex);
            adviceService = new AdviceService(openAiClient, contextAssembler);
            if (previous != null) {
                previous.close();
            }

            final boolean codexModeFinal = codexMode;
            SwingUtilities.invokeLater(() -> panel.getSettingsPanel().setAuthMode(
                    codexModeFinal
                            ? "Auth: ChatGPT OAuth (from ~/.codex/auth.json)"
                            : "Auth: API Key"));

            log.info("Leagues AI: loaded {} tasks, {} areas", tasks.size(), areas.size());

            SwingUtilities.invokeLater(() -> {
                if (tasks.isEmpty()) {
                    panel.getSettingsPanel().setDatabaseStatus(
                        "Database: not found (run scraper)", true);
                } else {
                    panel.getSettingsPanel().setDatabaseStatus(
                        "Database: " + tasks.size() + " tasks loaded", false);
                }
                if (apiKey == null || apiKey.isEmpty()) {
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
                        chatService = new ChatService(newClient, contextAssembler, taskRepo, vectorIndex);
                        adviceService = new AdviceService(newClient, contextAssembler);
                    }
                    usingCodexOauth = true;
                    if (old != null) {
                        try { old.close(); } catch (Exception ignored) {}
                    }
                    SwingUtilities.invokeLater(() -> {
                        panel.getSettingsPanel().setAuthMode("Auth: ChatGPT OAuth (from ~/.codex/auth.json)");
                        panel.getSettingsPanel().setSignInButtonText("Re-authenticate with ChatGPT");
                        panel.getSettingsPanel().setSignInButtonEnabled(true);
                        panel.getChatPanel().appendMessage("System", "Signed in successfully.");
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        panel.getSettingsPanel().setSignInButtonText("Sign in with ChatGPT");
                        panel.getSettingsPanel().setSignInButtonEnabled(true);
                        panel.getChatPanel().showError("Login timed out after 2 minutes. Check if Terminal opened and whether you completed the browser flow.");
                    });
                }
            } catch (Exception e) {
                log.error("Failed to launch Codex login", e);
                SwingUtilities.invokeLater(() -> {
                    panel.getSettingsPanel().setSignInButtonText("Sign in with ChatGPT");
                    panel.getSettingsPanel().setSignInButtonEnabled(true);
                    panel.getChatPanel().showError("Failed to launch Codex login: " + e.getMessage());
                });
            }
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

        // Advice refresh
        panel.getAdvicePanel().setOnRefresh(() -> {
            if (adviceService == null) {
                panel.getAdvicePanel().showError("Database not loaded yet.");
                return;
            }
            panel.getAdvicePanel().setLoading(true);
            llmExecutor.submit(() -> {
                try {
                    String advice = adviceService.getAdvice();
                    SwingUtilities.invokeLater(() -> {
                        panel.getAdvicePanel().setAdvice(advice);
                        panel.getAdvicePanel().setLoading(false);
                    });
                } catch (Exception e) {
                    log.error("Advice error", e);
                    SwingUtilities.invokeLater(() -> {
                        panel.getAdvicePanel().showError(e.getMessage());
                        panel.getAdvicePanel().setLoading(false);
                    });
                }
            });
        });

        // Settings — goal
        panel.getSettingsPanel().setOnGoalSet(goal -> {
            contextAssembler.setCurrentGoal(goal);
            panel.getAdvicePanel().setGoal(goal);
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
                    chatService = new ChatService(openAiClient, contextAssembler, taskRepo, vectorIndex);
                    adviceService = new AdviceService(openAiClient, contextAssembler);
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
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            xpMonitor.initialize();
        }
    }

    @Override
    protected void shutDown() throws Exception {
        eventBus.unregister(xpMonitor);
        eventBus.unregister(inventoryMonitor);
        eventBus.unregister(locationMonitor);
        eventBus.unregister(objectOverlay);
        eventBus.unregister(groundItemOverlay);

        overlayManager.remove(tileOverlay);
        overlayManager.remove(arrowOverlay);
        overlayManager.remove(npcOverlay);
        overlayManager.remove(objectOverlay);
        overlayManager.remove(groundItemOverlay);
        overlayManager.remove(minimapOverlay);
        overlayManager.remove(pathOverlay);
        overlayManager.remove(widgetOverlay);

        overlayController.clearAll();

        // Stop the sprite animation timer before disposing the panel.
        if (panel != null && panel.getSpriteRenderer() != null) {
            panel.getSpriteRenderer().dispose();
        }

        clientToolbar.removeNavigation(navButton);

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
