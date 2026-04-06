package com.leaguesai.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * LLM client that talks to the ChatGPT backend Responses API using OAuth
 * tokens harvested from the Codex CLI's auth.json. Mirrors the request shape
 * codex_cli_rs sends so the backend recognises us as a legitimate Codex
 * client.
 *
 * <p>Embeddings are not exposed by the Codex backend, so
 * {@link #getEmbedding(String)} throws and {@link #supportsEmbeddings()}
 * returns {@code false}. Callers using RAG must check the latter and
 * fall back to a non-RAG prompt path.
 */
@Slf4j
public class CodexOauthClient implements LlmClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String DEFAULT_BASE_URL = "https://chatgpt.com/backend-api/codex/";
    private static final String USER_AGENT = "codex_cli_rs/0.117.0 (Darwin 25.4.0; arm64) leagues-ai";

    private final String model;
    private final String baseUrl;
    private final CodexAuthStore authStore;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public CodexOauthClient(String model) {
        this(model, DEFAULT_BASE_URL);
    }

    public CodexOauthClient(String model, String baseUrl) {
        this(model, baseUrl, new CodexAuthStore());
    }

    public CodexOauthClient(String model, String baseUrl, CodexAuthStore authStore) {
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.authStore = authStore;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                // SSE streams can sit idle between events; allow long reads.
                .readTimeout(300, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    @Override
    public String chatCompletion(String systemPrompt, List<OpenAiClient.Message> messages) throws IOException {
        String requestJson = buildRequestBody(systemPrompt, messages);

        Response response = executeWithAuth(requestJson, false);
        if (response.code() == 401) {
            response.close();
            log.info("Codex OAuth got 401 — refreshing token and retrying once");
            authStore.refresh();
            response = executeWithAuth(requestJson, true);
        }

        try {
            if (!response.isSuccessful()) {
                String errBody = "";
                if (response.body() != null) {
                    try { errBody = response.body().string(); } catch (IOException ignored) {}
                }
                String snippet = errBody.length() > 300 ? errBody.substring(0, 300) : errBody;
                throw new IOException("Codex OAuth HTTP " + response.code() + ": " + snippet);
            }
            return readSseResponse(response);
        } finally {
            response.close();
        }
    }

    private Response executeWithAuth(String requestJson, boolean afterRefresh) throws IOException {
        String token = authStore.getAccessToken();
        String accountId = authStore.getAccountId();
        if (token == null || token.isEmpty()) {
            throw new IOException("No Codex OAuth access_token available");
        }
        Request.Builder b = new Request.Builder()
                .url(baseUrl + "responses")
                .header("Authorization", "Bearer " + token)
                .header("originator", "codex_cli_rs")
                .header("User-Agent", USER_AGENT)
                .header("OpenAI-Beta", "responses=experimental")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(requestJson, JSON));
        if (accountId != null && !accountId.isEmpty()) {
            b.header("ChatGPT-Account-ID", accountId);
        }
        return httpClient.newCall(b.build()).execute();
    }

    private String buildRequestBody(String systemPrompt, List<OpenAiClient.Message> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("instructions", systemPrompt);
        body.addProperty("stream", true);
        body.addProperty("store", false);
        body.addProperty("parallel_tool_calls", false);

        JsonArray input = new JsonArray();
        for (OpenAiClient.Message msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("type", "message");
            msgObj.addProperty("role", msg.getRole());
            JsonArray content = new JsonArray();
            JsonObject contentItem = new JsonObject();
            contentItem.addProperty("type",
                    "assistant".equals(msg.getRole()) ? "output_text" : "input_text");
            contentItem.addProperty("text", msg.getContent());
            content.add(contentItem);
            msgObj.add("content", content);
            input.add(msgObj);
        }
        body.add("input", input);
        body.add("tools", new JsonArray());
        body.addProperty("tool_choice", "auto");

        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", "medium");
        reasoning.addProperty("summary", "auto");
        body.add("reasoning", reasoning);

        return gson.toJson(body);
    }

    private String readSseResponse(Response response) throws IOException {
        ResponseBody respBody = response.body();
        if (respBody == null) {
            throw new IOException("Empty response body from Codex backend");
        }
        StringBuilder fullText = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(respBody.byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                try {
                    JsonObject event = gson.fromJson(data, JsonObject.class);
                    if (event == null) continue;
                    String type = event.has("type") ? event.get("type").getAsString() : "";
                    if ("response.output_text.delta".equals(type)) {
                        if (event.has("delta") && !event.get("delta").isJsonNull()) {
                            fullText.append(event.get("delta").getAsString());
                        }
                    } else if ("response.completed".equals(type)) {
                        if (fullText.length() == 0 && event.has("response")) {
                            JsonElement respEl = event.get("response");
                            if (respEl.isJsonObject()) {
                                extractTextFromCompletedEvent(respEl.getAsJsonObject(), fullText);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Best-effort: ignore malformed individual events.
                }
            }
        }
        return fullText.toString();
    }

    /**
     * Walk {@code response.output[]} for {@code message} items and append any
     * {@code output_text.text} content to {@code out}.
     */
    private void extractTextFromCompletedEvent(JsonObject response, StringBuilder out) {
        if (!response.has("output")) return;
        JsonElement outputEl = response.get("output");
        if (!outputEl.isJsonArray()) return;
        JsonArray output = outputEl.getAsJsonArray();
        for (JsonElement itemEl : output) {
            if (!itemEl.isJsonObject()) continue;
            JsonObject item = itemEl.getAsJsonObject();
            String itemType = item.has("type") ? item.get("type").getAsString() : "";
            if (!"message".equals(itemType)) continue;
            if (!item.has("content") || !item.get("content").isJsonArray()) continue;
            JsonArray content = item.getAsJsonArray("content");
            for (JsonElement cEl : content) {
                if (!cEl.isJsonObject()) continue;
                JsonObject c = cEl.getAsJsonObject();
                String cType = c.has("type") ? c.get("type").getAsString() : "";
                if ("output_text".equals(cType) && c.has("text") && !c.get("text").isJsonNull()) {
                    out.append(c.get("text").getAsString());
                }
            }
        }
    }

    @Override
    public float[] getEmbedding(String text) throws IOException {
        throw new UnsupportedOperationException("Codex OAuth mode does not support embeddings");
    }

    @Override
    public boolean supportsEmbeddings() {
        return false;
    }

    @Override
    public void close() {
        try {
            httpClient.dispatcher().executorService().shutdown();
        } catch (Exception ignored) {}
        try {
            httpClient.connectionPool().evictAll();
        } catch (Exception ignored) {}
        try {
            if (httpClient.cache() != null) {
                httpClient.cache().close();
            }
        } catch (IOException ignored) {}
    }
}
