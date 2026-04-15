package com.leaguesai.agent;

/**
 * Provides Leagues VI (Demonic Pacts) specific prompt sections.
 * Selected by {@link PromptBuilder} when {@link PlayerContext#isLeaguesMode()} is true.
 */
class LeaguesContextBuilder implements ModeContextBuilder {

    @Override
    public String buildIntro() {
        return "You are an expert OSRS Leagues VI (Demonic Pacts) coach.\n\n";
    }

    /**
     * Returns the Echo Boss reference table, injected after the coaching doctrine
     * so the LLM always has factual drop rates and access mechanics without hallucinating.
     */
    @Override
    public String buildExtraSection() {
        return PromptBuilder.buildEchoBossesSection();
    }

    /** Appends the player's current spendable league points to the Player State block. */
    @Override
    public String buildPlayerStateExtras(PlayerContext ctx) {
        return "- League Points: " + ctx.getLeaguePoints() + "\n";
    }
}
