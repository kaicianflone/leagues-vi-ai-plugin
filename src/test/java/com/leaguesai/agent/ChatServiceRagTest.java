package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.VectorIndex;
import com.leaguesai.data.model.Difficulty;
import com.leaguesai.data.model.Task;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * End-to-end RAG flow test for {@link ChatService}.
 *
 * <p>Uses a real {@link OpenAiClient} pointed at a {@link MockWebServer},
 * a real {@link VectorIndex} seeded with deterministic vectors, a mocked
 * {@link TaskRepository}, and a mocked {@link PlayerContextAssembler}.
 *
 * <p>Verifies the full pipeline:
 * embedding call → vector search → repo lookup → prompt assembly → chat call.
 */
public class ChatServiceRagTest {

    private MockWebServer server;
    private OpenAiClient openAiClient;
    private PlayerContextAssembler contextAssembler;
    private TaskRepository taskRepo;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/v1/").toString();
        openAiClient = new OpenAiClient("test-key", "gpt-4", baseUrl);
        openAiClient.setRetryDelayMs(10);

        contextAssembler = mock(PlayerContextAssembler.class);
        // Return a minimal but non-null PlayerContext (assemble() is called inside ChatService).
        PlayerContext ctx = PlayerContext.builder()
                .levels(new java.util.EnumMap<>(net.runelite.api.Skill.class))
                .xp(new java.util.EnumMap<>(net.runelite.api.Skill.class))
                .inventory(new HashMap<>())
                .equipment(new HashMap<>())
                .completedTasks(new java.util.HashSet<>())
                .unlockedAreas(new java.util.HashSet<>())
                .leaguePoints(0)
                .combatLevel(3)
                .currentGoal("test goal")
                .currentPlan(new java.util.ArrayList<>())
                .build();
        when(contextAssembler.assemble()).thenReturn(ctx);

        taskRepo = mock(TaskRepository.class);
    }

    @After
    public void tearDown() throws Exception {
        openAiClient.close();
        server.shutdown();
    }

    @Test
    public void ragFlow_callsEmbeddingThenChat_andIncludesTopTasksInPrompt() throws Exception {
        // Seed a real VectorIndex with three vectors. Query [1,0,0] should rank
        // task-a highest (cosine 1.0), task-b second, task-c last.
        Map<String, float[]> vectors = new LinkedHashMap<>();
        vectors.put("task-a", new float[]{1.0f, 0.0f, 0.0f});
        vectors.put("task-b", new float[]{0.9f, 0.1f, 0.0f});
        vectors.put("task-c", new float[]{0.0f, 1.0f, 0.0f});
        VectorIndex vectorIndex = new VectorIndex(vectors);

        // Repo: return a task for each id we expect to look up.
        Task taskA = Task.builder().id("task-a").name("Slay a Goblin")
                .description("Easy combat task").difficulty(Difficulty.EASY)
                .points(10).area("misthalin").category("combat").build();
        Task taskB = Task.builder().id("task-b").name("Mine some Copper")
                .description("Mine 5 copper ore").difficulty(Difficulty.EASY)
                .points(10).area("misthalin").category("skilling").build();
        Task taskC = Task.builder().id("task-c").name("Bake some Bread")
                .description("Cook bread").difficulty(Difficulty.EASY)
                .points(10).area("misthalin").category("skilling").build();
        when(taskRepo.getById("task-a")).thenReturn(taskA);
        when(taskRepo.getById("task-b")).thenReturn(taskB);
        when(taskRepo.getById("task-c")).thenReturn(taskC);

        // First response: embedding endpoint returns query vector aligned with task-a.
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"embedding\":[1.0,0.0,0.0]}]}"));
        // Second response: chat completion.
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Try Slay a Goblin first\"}}]}"));

        ChatService chat = new ChatService(openAiClient, contextAssembler, taskRepo, vectorIndex, null);
        String reply = chat.sendMessage("What should I do for combat?");

        assertEquals("Try Slay a Goblin first", reply);
        assertEquals("expected exactly 2 HTTP calls (embedding + chat)",
                2, server.getRequestCount());

        RecordedRequest embedReq = server.takeRequest();
        assertTrue("first call should be /embeddings",
                embedReq.getPath().endsWith("/embeddings"));
        String embedBody = embedReq.getBody().readUtf8();
        assertTrue("embedding request must include user text",
                embedBody.contains("What should I do for combat?"));

        RecordedRequest chatReq = server.takeRequest();
        assertTrue("second call should be /chat/completions",
                chatReq.getPath().endsWith("/chat/completions"));
        String chatBody = chatReq.getBody().readUtf8();
        // Top task name must appear in the system prompt sent to the chat endpoint.
        assertTrue("chat prompt must contain the top RAG task name, body=" + chatBody,
                chatBody.contains("Slay a Goblin"));

        // Verify the repo was queried for retrieved IDs.
        verify(taskRepo, atLeastOnce()).getById("task-a");
    }

    @Test
    public void ragFlow_skipsEmbedding_whenVectorIndexIsEmpty() throws Exception {
        VectorIndex emptyIndex = new VectorIndex(new HashMap<>());

        // Only one expected call: chat. No embedding.
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));

        ChatService chat = new ChatService(openAiClient, contextAssembler, taskRepo, emptyIndex, null);
        String reply = chat.sendMessage("hello");

        assertEquals("hi", reply);
        assertEquals("must not call embedding endpoint when index is empty",
                1, server.getRequestCount());
        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().endsWith("/chat/completions"));
        verify(taskRepo, never()).getById(anyString());
    }

    @Test
    public void ragFlow_fallsBackToPlainPrompt_whenEmbeddingCallFails() throws Exception {
        Map<String, float[]> vectors = new LinkedHashMap<>();
        vectors.put("task-a", new float[]{1.0f, 0.0f, 0.0f});
        VectorIndex vectorIndex = new VectorIndex(vectors);

        // Embedding call: 400 (non-retryable) → IOException → ChatService catches it.
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"bad request\",\"type\":\"invalid_request\"}}"));
        // Chat completion still proceeds.
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"plain reply\"}}]}"));

        ChatService chat = new ChatService(openAiClient, contextAssembler, taskRepo, vectorIndex, null);
        String reply;
        try {
            reply = chat.sendMessage("hello");
        } catch (IOException ioe) {
            fail("ChatService should swallow embedding failures, not propagate them: " + ioe.getMessage());
            return;
        }

        assertEquals("plain reply", reply);
        assertEquals(2, server.getRequestCount());
        // Repo never consulted since RAG retrieval failed.
        verify(taskRepo, never()).getById(anyString());

        // Drain both requests so leftover state doesn't bleed across tests.
        server.takeRequest();
        RecordedRequest chatReq = server.takeRequest();
        String body = chatReq.getBody().readUtf8();
        // Plain prompt — no "Relevant Tasks" section since RAG failed.
        assertFalse("system prompt should not include Relevant Tasks section on RAG failure",
                body.contains("## Relevant Tasks"));
    }
}
