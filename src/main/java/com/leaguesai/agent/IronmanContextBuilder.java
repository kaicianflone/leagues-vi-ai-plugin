package com.leaguesai.agent;

/**
 * Provides standard Ironman prompt sections (no leagues mechanics).
 * Selected by {@link PromptBuilder} when {@link PlayerContext#isLeaguesMode()} is false.
 */
class IronmanContextBuilder implements ModeContextBuilder {

    @Override
    public String buildIntro() {
        return "You are an expert OSRS Ironman planner.\n\n";
    }

    /** No extra section for standard ironman mode. */
    @Override
    public String buildExtraSection() {
        return "";
    }

    /** No extra player state lines for standard ironman mode. */
    @Override
    public String buildPlayerStateExtras(PlayerContext ctx) {
        return "";
    }
}
