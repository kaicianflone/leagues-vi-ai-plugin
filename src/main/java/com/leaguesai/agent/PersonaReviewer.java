package com.leaguesai.agent;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * Adversarially reviews a freshly built plan via a multi-persona LLM call.
 *
 * <p>Roleplays B0aty (HCIM safety), Faux (efficient skiller rates), and a
 * top UIM (movement-aware logistics). Returns a short verdict block that
 * the {@code GoalsPanel} renders as a "Coach review" banner above the
 * accordion.
 *
 * <p>Failures degrade gracefully — the plan still loads, the banner just
 * stays hidden.
 */
@Slf4j
public class PersonaReviewer {

    private final LlmClient llmClient;

    public PersonaReviewer(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Returns the raw verdict text (B0aty/Faux/UIM lines + Verdict line),
     * or {@code null} if the call failed. Caller decides whether to render.
     */
    public String review(String goal, List<PlannedStep> plan) {
        if (plan == null || plan.isEmpty()) {
            return null;
        }
        try {
            String prompt = PromptBuilder.buildPersonaReviewPrompt(goal, plan);
            String reply = llmClient.chatCompletion(
                    "You are three OSRS ironman experts giving an adversarial plan review. Be brutal and specific.",
                    Collections.singletonList(new OpenAiClient.Message("user", prompt))
            );
            log.info("PersonaReviewer: review complete ({} chars)", reply == null ? 0 : reply.length());
            return reply == null ? null : reply.trim();
        } catch (Exception e) {
            log.warn("PersonaReviewer: LLM call failed, no review banner: {}", e.getMessage());
            return null;
        }
    }
}
