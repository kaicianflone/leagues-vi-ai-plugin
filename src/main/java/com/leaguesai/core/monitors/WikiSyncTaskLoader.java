package com.leaguesai.core.monitors;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.leaguesai.agent.PlayerContextAssembler;
import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Task;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.config.RuneScapeProfileType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches completed league task IDs from the WikiSync server and marks them
 * in {@link PlayerContextAssembler} so the planner excludes them automatically.
 *
 * <p>The WikiSync plugin already syncs the player's varbits to the wiki server.
 * The server exposes {@code https://sync.runescape.wiki/runelite/player/<username>}
 * which returns {@code {"league_tasks": [3, 8, 51, ...]}} — the numeric wiki task
 * IDs for completed tasks. Our DB stores tasks as {@code "tbz-<num>"}, so
 * we strip the prefix to match.
 *
 * <p>This is called once on LOGGED_IN, then again after the DB loads, so
 * pre-existing completions are picked up immediately without needing the player
 * to complete a task during the current session.
 */
@Slf4j
@Singleton
public class WikiSyncTaskLoader {

    private static final String SYNC_BASE = "https://sync.runescape.wiki/runelite/player/";
    private static final Gson GSON = new Gson();

    private final Client client;
    private final OkHttpClient httpClient;

    private volatile PlayerContextAssembler contextAssembler;
    private volatile TaskRepository taskRepo;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wikisync-task-loader");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public WikiSyncTaskLoader(Client client, OkHttpClient httpClient) {
        this.client = client;
        this.httpClient = httpClient;
    }

    public void setContextAssembler(PlayerContextAssembler assembler) {
        this.contextAssembler = assembler;
    }

    public void setTaskRepository(TaskRepository repo) {
        this.taskRepo = repo;
    }

    /** Call from plugin shutDown() to stop any in-flight retry loops. */
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Fire-and-forget: fetches completed tasks on a background thread.
     *
     * <p>Must be called from the game thread (e.g. from {@code onGameStateChanged}).
     * Player name and profile type are captured here on the calling thread before
     * handing off to the executor — calling {@code client.*} from a background
     * thread is a RuneLite threading violation and can return stale data or crash.
     */
    public void loadCompletedTasks() {
        // Capture both values on the calling (game) thread before executor.submit().
        Player player = client.getLocalPlayer();
        String playerName = player != null ? player.getName() : null;
        if (playerName == null) {
            log.warn("WikiSyncTaskLoader: local player not available at login, skipping WikiSync fetch");
            return;
        }

        RuneScapeProfileType profile;
        try {
            profile = RuneScapeProfileType.getCurrent(client);
        } catch (Exception e) {
            profile = RuneScapeProfileType.STANDARD;
        }
        final String playerNameFinal = playerName;
        final RuneScapeProfileType profileFinal = profile;

        executor.submit(() -> fetchAndMark(playerNameFinal, profileFinal));
    }

    private void fetchAndMark(String username, RuneScapeProfileType profile) {
        String encodedName;
        try {
            encodedName = java.net.URLEncoder.encode(username, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            encodedName = username; // UTF-8 is always supported; fallback just in case
        }
        String url = SYNC_BASE + encodedName + "/" + profile.name();
        log.info("WikiSyncTaskLoader: fetching WikiSync data for profile {}", profile.name());

        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "leagues-vi-ai-plugin/1.0")
                    .build();

            String body;
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("WikiSyncTaskLoader: HTTP {} (profile={})", resp.code(), profile.name());
                    return;
                }
                body = resp.body().string();
            }

            JsonObject json = GSON.fromJson(body, JsonObject.class);
            JsonArray leagueTasks = json.getAsJsonArray("league_tasks");
            if (leagueTasks == null) {
                log.info("WikiSyncTaskLoader: no league_tasks field in response");
                return;
            }

            // Build set of completed numeric task IDs from wiki
            Set<Integer> completedWikiIds = new HashSet<>();
            for (JsonElement el : leagueTasks) {
                try {
                    completedWikiIds.add(el.getAsInt());
                } catch (Exception ignored) {}
            }
            log.info("WikiSyncTaskLoader: {} completed tasks from WikiSync", completedWikiIds.size());

            PlayerContextAssembler assembler = contextAssembler;
            TaskRepository repo = taskRepo;
            if (assembler == null || repo == null) {
                log.warn("WikiSyncTaskLoader: assembler or repo not set yet, skipping mark");
                return;
            }

            // Match wiki numeric IDs to our task IDs ("tbz-<num>")
            // Our IDs are formatted as "tbz-<wikiId>" — strip the prefix.
            List<Task> allTasks = repo.getAllTasks();
            if (allTasks == null) return;

            int marked = 0;
            for (Task t : allTasks) {
                String id = t.getId();
                if (id == null) continue;
                int wikiId = extractWikiId(id);
                if (wikiId >= 0 && completedWikiIds.contains(wikiId)) {
                    assembler.markTaskCompleted(id);
                    marked++;
                }
            }
            log.info("WikiSyncTaskLoader: marked {} tasks completed from WikiSync data", marked);

        } catch (Exception e) {
            log.warn("WikiSyncTaskLoader: failed to load completed tasks: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Extracts the numeric wiki ID from a task ID like {@code "tbz-3"}.
     * Returns -1 if the format doesn't match.
     */
    private static int extractWikiId(String taskId) {
        int dash = taskId.lastIndexOf('-');
        if (dash < 0) return -1;
        try {
            return Integer.parseInt(taskId.substring(dash + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
