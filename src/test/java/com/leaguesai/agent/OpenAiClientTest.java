package com.leaguesai.agent;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class OpenAiClientTest {

    private MockWebServer server;
    private OpenAiClient client;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/v1/").toString();
        client = new OpenAiClient("test-api-key", "gpt-4", baseUrl);
        // Use very short retry delay to keep tests fast
        client.setRetryDelayMs(10);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void testChatCompletion() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Hello there\"}}]}"));

        List<OpenAiClient.Message> messages = Arrays.asList(
                new OpenAiClient.Message("user", "Hi!")
        );

        String result = client.chatCompletion("You are helpful.", messages);

        assertEquals("Hello there", result);

        RecordedRequest request = server.takeRequest();
        String authHeader = request.getHeader("Authorization");
        assertNotNull("Authorization header should be present", authHeader);
        assertEquals("Bearer test-api-key", authHeader);
    }

    @Test
    public void testGetEmbedding() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}"));

        float[] embedding = client.getEmbedding("test text");

        assertNotNull(embedding);
        assertEquals(3, embedding.length);
        assertEquals(0.1f, embedding[0], 0.0001f);
        assertEquals(0.2f, embedding[1], 0.0001f);
        assertEquals(0.3f, embedding[2], 0.0001f);
    }

    @Test
    public void testGetEmbeddingUsesDedicatedEmbeddingModel() throws Exception {
        // Configured chat model is "gpt-4" — embeddings must NOT use it.
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}"));

        client.getEmbedding("hello world");

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue("embedding request must use text-embedding-3-small, body=" + body,
                body.contains("\"model\":\"text-embedding-3-small\""));
        assertFalse("embedding request must not reuse the chat model, body=" + body,
                body.contains("\"model\":\"gpt-4\""));
    }

    @Test
    public void testRetryOn429() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Rate limit exceeded\",\"type\":\"rate_limit_error\"}}"));
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Rate limit exceeded\",\"type\":\"rate_limit_error\"}}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Success after retries\"}}]}"));

        List<OpenAiClient.Message> messages = Arrays.asList(
                new OpenAiClient.Message("user", "Hello")
        );

        String result = client.chatCompletion("System prompt", messages);

        assertEquals("Success after retries", result);
        assertEquals("Should have made exactly 3 requests", 3, server.getRequestCount());
    }

    @Test
    public void testFailOn400() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Invalid request format\",\"type\":\"invalid_request_error\"}}"));

        List<OpenAiClient.Message> messages = Arrays.asList(
                new OpenAiClient.Message("user", "Hello")
        );

        try {
            client.chatCompletion("System prompt", messages);
            fail("Expected IOException to be thrown");
        } catch (IOException e) {
            assertTrue("Error message should contain HTTP 400", e.getMessage().contains("HTTP 400"));
            assertTrue("Error message should contain extracted error message",
                    e.getMessage().contains("Invalid request format"));
        }

        // Should only have made 1 request (no retries on 400)
        assertEquals("Should not retry on 400", 1, server.getRequestCount());
    }

    @Test
    public void testFailAfterMaxRetries() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Internal server error\",\"type\":\"server_error\"}}"));
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Internal server error\",\"type\":\"server_error\"}}"));
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Internal server error\",\"type\":\"server_error\"}}"));

        List<OpenAiClient.Message> messages = Arrays.asList(
                new OpenAiClient.Message("user", "Hello")
        );

        try {
            client.chatCompletion("System prompt", messages);
            fail("Expected IOException to be thrown after max retries");
        } catch (IOException e) {
            assertTrue("Error message should contain HTTP 500", e.getMessage().contains("HTTP 500"));
        }

        assertEquals("Should have made exactly 3 requests (all retries exhausted)", 3, server.getRequestCount());
    }
}
