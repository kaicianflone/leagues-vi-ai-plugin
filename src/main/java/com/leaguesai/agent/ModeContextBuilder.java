package com.leaguesai.agent;

/**
 * Provides the mode-specific sections of the system prompt. Implementations
 * are selected by {@link PromptBuilder} based on {@link PlayerContext#isLeaguesMode()}.
 *
 * <p>This interface isolates the Leagues VI vs Ironman branching that would
 * otherwise scatter {@code if (leaguesMode)} blocks through
 * {@code PromptBuilder.buildSystemPromptImpl()}.
 */
interface ModeContextBuilder {

    /** Opening sentence establishing the LLM's coaching role for this mode. */
    String buildIntro();

    /**
     * Mode-specific section appended after the coaching doctrine block.
     * Returns an empty string for modes that have no such section.
     */
    String buildExtraSection();

    /**
     * Additional lines appended inside the Player State block.
     * Returns an empty string for modes where no extra state lines apply.
     */
    String buildPlayerStateExtras(PlayerContext ctx);
}
