package com.leaguesai.scraper;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TaskItemExtractor}.
 */
public class TaskItemExtractorTest {

    private TaskItemExtractor extractor;

    @Before
    public void setUp() {
        extractor = new TaskItemExtractor();
    }

    @Test
    public void testEquipBandosChestplate() {
        List<TaskItemExtractor.ItemTarget> targets =
                extractor.extract("Equip a Bandos chestplate", null);
        assertEquals(1, targets.size());
        assertEquals("Bandos chestplate", targets.get(0).name);
    }

    @Test
    public void testWieldTwistedBow() {
        List<TaskItemExtractor.ItemTarget> targets =
                extractor.extract("Wield a Twisted bow", null);
        assertEquals(1, targets.size());
        assertEquals("Twisted bow", targets.get(0).name);
    }

    @Test
    public void testNoVerbMatchReturnsEmpty_completeDiary() {
        List<TaskItemExtractor.ItemTarget> targets =
                extractor.extract("Complete the Elite Morytania Diary", null);
        assertTrue("Expected empty list for non-verb task", targets.isEmpty());
    }

    @Test
    public void testNoVerbMatchReturnsEmpty_killDragon() {
        List<TaskItemExtractor.ItemTarget> targets =
                extractor.extract("Kill a dragon with fire", null);
        assertTrue("'kill' is not in the allowlist; expected empty list", targets.isEmpty());
    }

    @Test
    public void testObtainFireCape() {
        List<TaskItemExtractor.ItemTarget> targets =
                extractor.extract("Obtain a fire cape", null);
        assertEquals(1, targets.size());
        assertEquals("fire cape", targets.get(0).name);
    }

    @Test
    public void testDeduplication_sameItemInNameAndDescription() {
        List<TaskItemExtractor.ItemTarget> targets =
                extractor.extract(
                        "Equip a Dragon platebody",
                        "Equip a Dragon platebody in the Kourend area.");
        assertEquals("Duplicate item across name + description should produce one entry",
                1, targets.size());
        assertEquals("Dragon platebody", targets.get(0).name);
    }

    @Test
    public void testWikiItemIdInitialisedToZero() {
        List<TaskItemExtractor.ItemTarget> targets =
                extractor.extract("Acquire a Berserker ring", null);
        assertFalse(targets.isEmpty());
        assertEquals("wikiItemId should default to 0 before resolution",
                0, targets.get(0).wikiItemId);
    }

    @Test
    public void testNullInputsReturnEmptyList() {
        List<TaskItemExtractor.ItemTarget> targets = extractor.extract(null, null);
        assertNotNull(targets);
        assertTrue(targets.isEmpty());
    }

    @Test
    public void testEmptyInputsReturnEmptyList() {
        List<TaskItemExtractor.ItemTarget> targets = extractor.extract("", "");
        assertNotNull(targets);
        assertTrue(targets.isEmpty());
    }

    @Test
    public void testWearItem() {
        List<TaskItemExtractor.ItemTarget> targets =
                extractor.extract("Wear an Amulet of glory", null);
        assertEquals(1, targets.size());
        assertEquals("Amulet of glory", targets.get(0).name);
    }
}
