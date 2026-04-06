package com.leaguesai.agent;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;

/**
 * Heartbeat's deeper-thinking sibling. Fires a real LLM call every 5 minutes
 * (driven by {@code HeartbeatTicker}) to produce a richer one-sentence
 * coach pulse that overrides the local heartbeat for that tick.
 *
 * <p>Replaces the old {@code AdviceService} — the standalone "refresh advice"
 * button is gone; the goals/chat panels surface the pulse instead.
 *
 * <p>Failures are caller's problem: returns {@code null} on error so the
 * ticker can fall back to the local heartbeat string.
 */
@Slf4j
@Singleton
public class CoachPulseService {

    private final LlmClient llmClient;
    private final PlayerContextAssembler contextAssembler;

    @Inject
    public CoachPulseService(LlmClient llmClient, PlayerContextAssembler contextAssembler) {
        this.llmClient = llmClient;
        this.contextAssembler = contextAssembler;
    }

    /**
     * One-shot LLM call. MUST be invoked off the EDT. Returns the trimmed
     * one-sentence pulse, or {@code null} on any failure.
     */
    public String pulse() {
        try {
            PlayerContext ctx = contextAssembler.assemble();
            String systemPrompt = PromptBuilder.buildSystemPrompt(ctx);
            String userPrompt = PromptBuilder.buildCoachPulsePrompt(ctx);

            String reply = llmClient.chatCompletion(
                    systemPrompt,
                    Collections.singletonList(new OpenAiClient.Message("user", userPrompt))
            );
            if (reply == null) return null;
            String trimmed = reply.trim();
            if (trimmed.length() > 110) {
                trimmed = trimmed.substring(0, 107) + "...";
            }
            return trimmed;
        } catch (Exception e) {
            log.warn("CoachPulseService: pulse failed: {}", e.getMessage());
            return null;
        }
    }
}
