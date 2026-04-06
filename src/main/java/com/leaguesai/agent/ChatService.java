package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.VectorIndex;
import com.leaguesai.data.model.Task;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class ChatService {

    static final int MAX_HISTORY = 20;

    private final OpenAiClient openAiClient;
    private final PlayerContextAssembler contextAssembler;
    private final TaskRepository taskRepo;
    private final VectorIndex vectorIndex;

    // Thread-safe: all access guarded by synchronized blocks
    private final List<OpenAiClient.Message> conversationHistory =
            Collections.synchronizedList(new ArrayList<>());

    @Inject
    public ChatService(OpenAiClient openAiClient,
                       PlayerContextAssembler contextAssembler,
                       TaskRepository taskRepo,
                       VectorIndex vectorIndex) {
        this.openAiClient = openAiClient;
        this.contextAssembler = contextAssembler;
        this.taskRepo = taskRepo;
        this.vectorIndex = vectorIndex;
    }

    /**
     * Send a user message and return the assistant's reply.
     *
     * Thread-safety: conversationHistory mutations are synchronized. The network
     * call to OpenAI is made OUTSIDE the synchronized block so the lock is not
     * held during a potentially long I/O operation.
     */
    public String sendMessage(String userMessage) throws Exception {
        // RAG: find relevant tasks via semantic search. Done OUTSIDE the lock
        // because both the embedding network call and the vector search may take
        // a non-trivial amount of time. Failures degrade gracefully — chat still
        // works without RAG context.
        List<Task> relevantTasks = Collections.emptyList();
        try {
            if (vectorIndex != null && !vectorIndex.isEmpty()) {
                float[] queryEmbedding = openAiClient.getEmbedding(userMessage);
                List<String> taskIds = vectorIndex.searchSimilar(queryEmbedding, 5);
                relevantTasks = taskIds.stream()
                        .map(taskRepo::getById)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("Vector search failed, proceeding without RAG: {}", e.getMessage());
        }

        // Build the system prompt and take a snapshot — all inside the lock
        List<OpenAiClient.Message> snapshot;
        String systemPrompt;

        synchronized (conversationHistory) {
            conversationHistory.add(new OpenAiClient.Message("user", userMessage));

            // Trim oldest messages when history exceeds the cap
            while (conversationHistory.size() > MAX_HISTORY) {
                conversationHistory.remove(0);
            }

            // Assemble player context and build system prompt while still in sync
            // (contextAssembler.assemble() routes through ClientThread — safe to call here)
            PlayerContext ctx = contextAssembler.assemble();
            systemPrompt = PromptBuilder.buildSystemPrompt(ctx, relevantTasks);

            // Snapshot: copy the list so the network call doesn't need the lock
            snapshot = new ArrayList<>(conversationHistory);
        }

        // Network call OUTSIDE the lock — may take seconds
        String response = openAiClient.chatCompletion(systemPrompt, snapshot);

        // Re-acquire lock to record assistant reply
        synchronized (conversationHistory) {
            conversationHistory.add(new OpenAiClient.Message("assistant", response));

            // Trim again in case concurrent calls pushed history over the cap
            while (conversationHistory.size() > MAX_HISTORY) {
                conversationHistory.remove(0);
            }
        }

        return response;
    }

    /**
     * Clear the conversation history.
     */
    public void clearHistory() {
        synchronized (conversationHistory) {
            conversationHistory.clear();
        }
    }
}
