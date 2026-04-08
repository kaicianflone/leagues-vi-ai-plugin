package com.leaguesai.scraper;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TaskNormalizer}.
 */
public class TaskNormalizerTest {

    // ------------------------------------------------------------------
    // parseSkillRequirements
    // ------------------------------------------------------------------

    @Test
    public void testParseSkillRequirements_twoSkills() {
        Map<String, Integer> result = TaskNormalizer.parseSkillRequirements("50 fishing 40 cooking");
        assertEquals("Expected 2 skill entries", 2, result.size());
        assertEquals(Integer.valueOf(50), result.get("fishing"));
        assertEquals(Integer.valueOf(40), result.get("cooking"));
    }

    @Test
    public void testParseSkillRequirements_singleSkill() {
        Map<String, Integer> result = TaskNormalizer.parseSkillRequirements("70 Agility");
        assertEquals(1, result.size());
        assertEquals(Integer.valueOf(70), result.get("agility"));
    }

    @Test
    public void testParseSkillRequirements_mixedCase() {
        Map<String, Integer> result = TaskNormalizer.parseSkillRequirements("60 Woodcutting");
        assertTrue(result.containsKey("woodcutting"));
        assertEquals(Integer.valueOf(60), result.get("woodcutting"));
    }

    @Test
    public void testParseSkillRequirements_emptyString() {
        Map<String, Integer> result = TaskNormalizer.parseSkillRequirements("");
        assertNotNull(result);
        assertTrue("Empty input should yield empty map", result.isEmpty());
    }

    @Test
    public void testParseSkillRequirements_nullInput() {
        Map<String, Integer> result = TaskNormalizer.parseSkillRequirements(null);
        assertNotNull(result);
        assertTrue("Null input should yield empty map", result.isEmpty());
    }

    @Test
    public void testParseSkillRequirements_noMatches() {
        Map<String, Integer> result = TaskNormalizer.parseSkillRequirements("None");
        assertTrue("No matching pattern should yield empty map", result.isEmpty());
    }

    @Test
    public void testParseSkillRequirements_runecraftingAliasedToRunecraft() {
        // The OSRS Wiki uses "Runecrafting" but RuneLite's Skill enum is
        // RUNECRAFT. Without alias normalization, the planner's skillsMet
        // check would fail to resolve "runecrafting" to a Skill value and
        // silently skip the requirement, over-recommending tasks the player
        // can't actually complete.
        Map<String, Integer> result = TaskNormalizer.parseSkillRequirements("77 Runecrafting");
        assertEquals(1, result.size());
        assertTrue("runecrafting should be aliased to runecraft",
                result.containsKey("runecraft"));
        assertFalse("raw 'runecrafting' key must not leak through",
                result.containsKey("runecrafting"));
        assertEquals(Integer.valueOf(77), result.get("runecraft"));
    }

    @Test
    public void testParseSkillRequirements_knownSkillsPassThrough() {
        // Sanity check: every other OSRS skill name maps to a RuneLite enum
        // value once lowercased, so no alias should be needed.
        Map<String, Integer> result = TaskNormalizer.parseSkillRequirements(
                "50 fishing 40 cooking 30 hitpoints 20 construction");
        assertEquals(4, result.size());
        assertTrue(result.containsKey("fishing"));
        assertTrue(result.containsKey("cooking"));
        assertTrue(result.containsKey("hitpoints"));
        assertTrue(result.containsKey("construction"));
    }

    // ------------------------------------------------------------------
    // normalizeDifficulty
    // ------------------------------------------------------------------

    @Test
    public void testNormalizeDifficulty_easyMixedCase() {
        assertEquals("easy", TaskNormalizer.normalizeDifficulty("Easy"));
        assertEquals("easy", TaskNormalizer.normalizeDifficulty("EASY"));
        assertEquals("easy", TaskNormalizer.normalizeDifficulty("easy"));
    }

    @Test
    public void testNormalizeDifficulty_hardMixedCase() {
        assertEquals("hard", TaskNormalizer.normalizeDifficulty("HARD"));
        assertEquals("hard", TaskNormalizer.normalizeDifficulty("Hard"));
        assertEquals("hard", TaskNormalizer.normalizeDifficulty("hard"));
    }

    @Test
    public void testNormalizeDifficulty_medium() {
        assertEquals("medium", TaskNormalizer.normalizeDifficulty("Medium"));
    }

    @Test
    public void testNormalizeDifficulty_elite() {
        assertEquals("elite", TaskNormalizer.normalizeDifficulty("Elite"));
    }

    @Test
    public void testNormalizeDifficulty_master() {
        assertEquals("master", TaskNormalizer.normalizeDifficulty("Master"));
    }

    @Test
    public void testNormalizeDifficulty_unknownDefaultsToEasy() {
        assertEquals("easy", TaskNormalizer.normalizeDifficulty("unknown"));
        assertEquals("easy", TaskNormalizer.normalizeDifficulty("beginner"));
        assertEquals("easy", TaskNormalizer.normalizeDifficulty(""));
    }

    @Test
    public void testNormalizeDifficulty_nullDefaultsToEasy() {
        assertEquals("easy", TaskNormalizer.normalizeDifficulty(null));
    }
}
