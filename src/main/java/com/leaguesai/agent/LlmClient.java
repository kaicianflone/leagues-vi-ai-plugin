package com.leaguesai.agent;

import java.io.IOException;
import java.util.List;

/**
 * Common interface implemented by every LLM transport the plugin can speak to.
 *
 * <p>Two implementations exist today:
 * <ul>
 *   <li>{@link OpenAiClient} — platform API key against api.openai.com</li>
 *   <li>{@link CodexOauthClient} — ChatGPT OAuth tokens against
 *       chatgpt.com/backend-api/codex/responses (Responses API)</li>
 * </ul>
 *
 * <p>Embeddings are optional: only the OpenAI platform endpoint exposes them,
 * so callers must check {@link #supportsEmbeddings()} before calling
 * {@link #getEmbedding(String)}.
 */
public interface LlmClient {

    String chatCompletion(String systemPrompt, List<OpenAiClient.Message> messages) throws IOException;

    float[] getEmbedding(String text) throws IOException;

    boolean supportsEmbeddings();

    default void close() {}
}
