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
     * Parses a skill-requirement string such as {@code "50 Fishing 40 Cooking"}
     * and returns a map of skill-name (lower-cased) → required level.
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
