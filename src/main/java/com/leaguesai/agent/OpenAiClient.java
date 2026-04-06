package com.leaguesai.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OpenAiClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RETRIES = 3;

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final Gson gson;

    // Configurable for testing — default 1000ms base delay
    private long retryDelayMs = 1000L;

    public OpenAiClient(String apiKey, String model) {
        this(apiKey, model, "https://api.openai.com/v1/");
    }

    public OpenAiClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    /** Override base retry delay — intended for tests only. */
    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    /**
     * Shuts down the underlying OkHttp dispatcher and connection pool. Must be
     * called when an OpenAiClient is replaced or the plugin is shutting down,
     * otherwise the dispatcher's executor service and idle connections will
     * leak.
     */
    public void close() {
        try {
            httpClient.dispatcher().executorService().shutdown();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
        try {
            httpClient.connectionPool().evictAll();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
        try {
            if (httpClient.cache() != null) {
                httpClient.cache().close();
            }
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    @Data
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
    }

    /**
     * Send a chat completion request and return the assistant's reply content.
     */
    public String chatCompletion(String systemPrompt, List<Message> messages) throws IOException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);

        JsonArray msgs = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        msgs.add(systemMsg);

        for (Message message : messages) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", message.getRole());
            msg.addProperty("content", message.getContent());
            msgs.add(msg);
        }
        requestBody.add("messages", msgs);

        Request request = new Request.Builder()
                .url(baseUrl + "chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(requestBody), JSON))
                .build();

        String responseBody = executeWithRetry(request);

        JsonObject json = gson.fromJson(responseBody, JsonObject.class);
        return json.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
    }

    /**
     * Request an embedding vector for the given text.
     */
    public float[] getEmbedding(String text) throws IOException {
        JsonObject requestBody = new JsonObject();
        // OpenAI's embeddings endpoint rejects chat models. Hardcode the
        // dedicated embedding model regardless of the configured chat model.
        requestBody.addProperty("model", "text-embedding-3-small");
        requestBody.addProperty("input", text);

        Request request = new Request.Builder()
                .url(baseUrl + "embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(requestBody), JSON))
                .build();

        String responseBody = executeWithRetry(request);

        JsonObject json = gson.fromJson(responseBody, JsonObject.class);
        JsonArray embeddingArray = json.getAsJsonArray("data")
                .get(0).getAsJsonObject()
                .getAsJsonArray("embedding");

        float[] result = new float[embeddingArray.size()];
        for (int i = 0; i < embeddingArray.size(); i++) {
            result[i] = embeddingArray.get(i).getAsFloat();
        }
        return result;
    }

    /**
     * Execute the request with retry logic for 429 and 5xx errors.
     * Max 3 attempts with exponential backoff: retryDelayMs, 2x, 4x.
     */
    private String executeWithRetry(Request request) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                int code = response.code();

                if (response.isSuccessful()) {
                    return body;
                }

                // Retry on 429 (rate limit) and 5xx (server errors)
                if (code == 429 || code >= 500) {
                    lastError = new IOException("HTTP " + code + ": " + extractErrorMessage(body));
                    if (attempt < MAX_RETRIES - 1) {
                        try {
                            Thread.sleep(retryDelayMs << attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted during retry backoff", ie);
                        }
                        continue;
                    }
                }

                // Non-retryable error (4xx except 429) — throw immediately
                throw new IOException("HTTP " + code + ": " + extractErrorMessage(body));
            }
        }
        throw lastError != null ? lastError : new IOException("Request failed after retries");
    }

    /**
     * Attempt to extract the human-readable error message from an OpenAI error response body.
     * Falls back to a truncated raw body if parsing fails.
     */
    private String extractErrorMessage(String body) {
        try {
            JsonObject json = gson.fromJson(body, JsonObject.class);
            if (json != null && json.has("error")) {
                JsonObject error = json.getAsJsonObject("error");
                if (error.has("message")) {
                    return error.get("message").getAsString();
                }
            }
        } catch (Exception ignored) {
            // Fall through to raw body
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
