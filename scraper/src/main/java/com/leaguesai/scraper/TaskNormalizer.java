package com.leaguesai.scraper;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static utilities for normalizing raw task data scraped from the OSRS Wiki.
 */
public class TaskNormalizer {

    private static final Pattern SKILL_REQ_PATTERN = Pattern.compile("(\\d+)\\s+(\\w+)");

    /**
     * Wiki-to-RuneLite skill name aliases. Most wiki names match the RuneLite
     * {@code Skill} enum directly once lowercased, but a few are spelled
     * differently. Adding the canonical name here at scrape time means every
     * downstream consumer (planner, prompt builder, tests) sees a single shape.
     *
     * <p>{@code "runecrafting"} → {@code "runecraft"} is the main offender
     * because the OSRS Wiki uses the in-game display name "Runecrafting" while
     * RuneLite's {@code Skill} enum is {@code RUNECRAFT}. Without this
     * normalization, the planner's {@code skillsMet} check silently skips
     * requirements it can't resolve and recommends unachievable tasks.
     */
    private static final Map<String, String> SKILL_ALIASES = new HashMap<>();
    static {
        SKILL_ALIASES.put("runecrafting", "runecraft");
    }

    /**
     * Parses a skill-requirement string such as {@code "50 Fishing 40 Cooking"}
     * and returns a map of skill-name (lower-cased, alias-normalised) → required
     * level. The returned key is guaranteed to match a {@code Skill} enum value
     * when uppercased, so downstream code can call {@code Skill.valueOf(key.toUpperCase())}
     * without needing its own alias table.
     *
     * @param reqText raw requirement text; may be null or empty
     * @return map of skill name → level; empty map if nothing could be parsed
     */
    public static Map<String, Integer> parseSkillRequirements(String reqText) {
        Map<String, Integer> result = new HashMap<>();
        if (reqText == null || reqText.isEmpty()) {
            return result;
        }

        Matcher matcher = SKILL_REQ_PATTERN.matcher(reqText);
        while (matcher.find()) {
            int level = Integer.parseInt(matcher.group(1));
            String skill = matcher.group(2).toLowerCase();
            skill = SKILL_ALIASES.getOrDefault(skill, skill);
            result.put(skill, level);
        }
        return result;
    }

    /**
     * Normalizes a raw difficulty string to one of: easy, medium, hard, elite, master.
     * Comparison is case-insensitive. Unrecognized values default to {@code "easy"}.
     *
     * @param raw raw difficulty string; may be null
     * @return normalized difficulty string
     */
    public static String normalizeDifficulty(String raw) {
        if (raw == null) {
            return "easy";
        }
        switch (raw.trim().toLowerCase()) {
            case "easy":   return "easy";
            case "medium": return "medium";
            case "hard":   return "hard";
            case "elite":  return "elite";
            case "master": return "master";
            default:       return "easy";
        }
    }
}
