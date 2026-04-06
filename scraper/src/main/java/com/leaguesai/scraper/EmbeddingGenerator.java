package com.leaguesai.scraper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Generates text embeddings via OpenAI's {@code text-embedding-3-small} model
 * and serializes the resulting float vector as little-endian bytes.
 */
public class EmbeddingGenerator {

    private static final String EMBEDDING_URL = "https://api.openai.com/v1/embeddings";
    private static final String MODEL = "text-embedding-3-small";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final OkHttpClient client;

    /**
     * @param apiKey OpenAI API key (must not be null or empty)
     */
    public EmbeddingGenerator(String apiKey) {
        this.apiKey = apiKey;
        this.client = new OkHttpClient();
    }

    /**
     * Calls the OpenAI embeddings endpoint and returns the 1536-float result
     * serialized as a little-endian {@code float[]} packed into a {@code byte[]}.
     *
     * @param text input text to embed
     * @return 1536 * 4 = 6144 bytes representing the embedding vector
     * @throws IOException on network or API errors
     */
    public byte[] generate(String text) throws IOException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", MODEL);
        requestBody.addProperty("input", text);

        Request request = new Request.Builder()
                .url(EMBEDDING_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("OpenAI API error " + response.code() + ": " +
                        (response.body() != null ? response.body().string() : "(no body)"));
            }

            String responseBody = response.body().string();
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray embedding = json
                    .getAsJsonArray("data")
                    .get(0).getAsJsonObject()
                    .getAsJsonArray("embedding");

            int dimensions = embedding.size();
            ByteBuffer buffer = ByteBuffer.allocate(dimensions * Float.BYTES);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < dimensions; i++) {
                buffer.putFloat(embedding.get(i).getAsFloat());
            }
            return buffer.array();
        }
    }
}
