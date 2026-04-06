package com.leaguesai.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * Reads and refreshes OAuth tokens stored at {@code ~/.codex/auth.json} by the
 * Codex CLI. The plugin uses these tokens to talk to the ChatGPT backend's
 * Responses API instead of requiring a separate platform API key.
 *
 * <p>Refresh is synchronized so concurrent {@code refresh()} callers can't
 * race and double-rotate the refresh token (which would invalidate one of
 * them).
 */
@Slf4j
public class CodexAuthStore {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token";
    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";

    private final Path authFile;
    private final OkHttpClient httpClient;
    private final Gson gson;

    private volatile JsonObject root;

    public CodexAuthStore() {
        this(defaultAuthFile());
    }

    public CodexAuthStore(Path authFile) {
        this.authFile = authFile;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    private static Path defaultAuthFile() {
        return new File(System.getProperty("user.home"), ".codex/auth.json").toPath();
    }

    /**
     * Returns true if {@code ~/.codex/auth.json} exists and contains a
     * non-empty {@code tokens.access_token}.
     */
    public static boolean hasValidAuth() {
        try {
            Path p = defaultAuthFile();
            if (!Files.exists(p)) {
                return false;
            }
            String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            JsonObject root = new Gson().fromJson(content, JsonObject.class);
            if (root == null || !root.has("tokens")) {
                return false;
            }
            JsonObject tokens = root.getAsJsonObject("tokens");
            return tokens != null
                    && tokens.has("access_token")
                    && !tokens.get("access_token").isJsonNull()
                    && !tokens.get("access_token").getAsString().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void load() {
        try {
            String content = new String(Files.readAllBytes(authFile), StandardCharsets.UTF_8);
            this.root = gson.fromJson(content, JsonObject.class);
            if (this.root == null) {
                this.root = new JsonObject();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + authFile + ": " + e.getMessage(), e);
        }
    }

    private JsonObject tokens() {
        if (root == null || !root.has("tokens") || !root.get("tokens").isJsonObject()) {
            throw new IllegalStateException("auth.json missing tokens object");
        }
        return root.getAsJsonObject("tokens");
    }

    public synchronized String getAccessToken() {
        JsonObject t = tokens();
        return t.has("access_token") && !t.get("access_token").isJsonNull()
                ? t.get("access_token").getAsString() : null;
    }

    public synchronized String getAccountId() {
        JsonObject t = tokens();
        return t.has("account_id") && !t.get("account_id").isJsonNull()
                ? t.get("account_id").getAsString() : null;
    }

    public synchronized String getRefreshToken() {
        JsonObject t = tokens();
        return t.has("refresh_token") && !t.get("refresh_token").isJsonNull()
                ? t.get("refresh_token").getAsString() : null;
    }

    /**
     * Refreshes the OAuth tokens by calling auth.openai.com and atomically
     * writes the rotated id_token / access_token / refresh_token back to
     * auth.json. Other top-level fields (auth_mode, last_refresh, etc.) are
     * preserved.
     */
    public synchronized void refresh() throws IOException {
        String currentRefresh = getRefreshToken();
        if (currentRefresh == null || currentRefresh.isEmpty()) {
            throw new IOException("No refresh_token in auth.json — cannot refresh");
        }

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("client_id", CLIENT_ID);
        reqBody.addProperty("grant_type", "refresh_token");
        reqBody.addProperty("refresh_token", currentRefresh);

        Request request = new Request.Builder()
                .url(OAUTH_TOKEN_URL)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(reqBody), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Token refresh failed: HTTP " + response.code());
            }
            JsonObject json = gson.fromJson(body, JsonObject.class);
            if (json == null) {
                throw new IOException("Token refresh returned empty body");
            }

            JsonObject tokens = tokens();
            if (json.has("id_token") && !json.get("id_token").isJsonNull()) {
                tokens.addProperty("id_token", json.get("id_token").getAsString());
            }
            if (json.has("access_token") && !json.get("access_token").isJsonNull()) {
                tokens.addProperty("access_token", json.get("access_token").getAsString());
            }
            if (json.has("refresh_token") && !json.get("refresh_token").isJsonNull()) {
                tokens.addProperty("refresh_token", json.get("refresh_token").getAsString());
            }
            root.add("tokens", tokens);
            root.addProperty("last_refresh", java.time.Instant.now().toString());

            writeAtomically(root);
            log.info("Refreshed Codex OAuth tokens");
        }
    }

    private void writeAtomically(JsonObject newRoot) throws IOException {
        Path parent = authFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Path tmp = parent != null
                ? Files.createTempFile(parent, "auth", ".json.tmp")
                : Files.createTempFile("auth", ".json.tmp");
        try {
            Files.write(tmp, gson.toJson(newRoot).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, authFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, authFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }
}
