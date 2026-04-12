package com.leaguesai.agent;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * Adversarially reviews a freshly built plan via a multi-persona LLM call.
 *
 * <p>Uses the active coaching personas configured in {@link com.leaguesai.data.UserPreferences}.
 * Each persona critiques the plan from its own lens; the review ends with a
 * {@code Verdict: keep | revise | rebuild} line that the chat service uses to
 * decide whether to refine the plan.
 *
 * <p>Failures degrade gracefully — the plan still loads, the banner just
 * stays hidden.
 */
@Slf4j
public class PersonaReviewer {

    private final LlmClient llmClient;
    private volatile com.leaguesai.data.UserPreferences userPreferences;

    public PersonaReviewer(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void setUserPreferences(com.leaguesai.data.UserPreferences prefs) {
        this.userPreferences = prefs;
    }

    /**
     * Returns the raw verdict text (one line per active persona + Verdict line),
     * or {@code null} if the call failed. Caller decides whether to render.
     */
    public String review(String goal, List<PlannedStep> plan) {
        return review(goal, plan, null);
    }

    /**
     * Review with optional prior critique for iterative refinement rounds.
     * {@code priorCritique} is appended to the prompt so personas can acknowledge
     * what changed (or why they still disagree).
     */
    public String review(String goal, List<PlannedStep> plan, String priorCritique) {
        if (plan == null || plan.isEmpty()) {
            return null;
        }
        try {
            java.util.List<String> personas = userPreferences != null
                    ? userPreferences.getSelectedPersonas() : null;
            String prompt = PromptBuilder.buildPersonaReviewPrompt(goal, plan, personas, priorCritique);
            String systemMsg = "You are OSRS Leagues VI coaching experts giving an adversarial plan review. "
                    + "Be brutal and specific. Follow the output format exactly.";
            String reply = llmClient.chatCompletion(
                    systemMsg,
                    Collections.singletonList(new OpenAiClient.Message("user", prompt))
            );
            log.info("PersonaReviewer: review complete ({} chars)", reply == null ? 0 : reply.length());
            return reply == null ? null : reply.trim();
        } catch (Exception e) {
            log.warn("PersonaReviewer: LLM call failed, no review banner: {}", e.getMessage());
            return null;
        }
    }

    /** Extracts the verdict token from a review string. Returns "keep" on parse failure. */
    public static String extractVerdict(String review) {
        if (review == null) return "keep";
        for (String line : review.split("\n")) {
            String trimmed = line.trim().toLowerCase();
            if (trimmed.startsWith("verdict:")) {
                String v = trimmed.substring("verdict:".length()).trim();
                if (v.contains("rebuild")) return "rebuild";
                if (v.contains("revise")) return "revise";
                return "keep";
            }
        }
        return "keep";
    }
}
