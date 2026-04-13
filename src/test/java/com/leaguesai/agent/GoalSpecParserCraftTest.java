package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.ItemDependency;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for the CRAFT goal-type arm of {@link GoalSpecParser}.
 *
 * <p>Verifies that crafting verbs (make, craft, smith, fletch, brew, cook)
 * route the parser to {@link GoalType#CRAFT} instead of {@link GoalType#ITEM},
 * and that non-crafting intent still routes to {@link GoalType#ITEM}.
 */
public class GoalSpecParserCraftTest {

    private TaskRepository repo;
    private ItemDependencyGraph graph;

    @Before
    public void setUp() {
        repo = mock(TaskRepository.class);
        when(repo.getAllRelics()).thenReturn(Collections.emptyList());
        when(repo.getAllAreas()).thenReturn(Collections.emptyList());
        when(repo.getAllPacts()).thenReturn(Collections.emptyList());
        when(repo.findRelicByName(anyString())).thenReturn(java.util.Optional.empty());
        when(repo.findAreaByName(anyString())).thenReturn(java.util.Optional.empty());
        when(repo.findPactByName(anyString())).thenReturn(java.util.Optional.empty());

        // Graph knows about "staff of air" and "iron platebody"
        graph = mock(ItemDependencyGraph.class);
        when(graph.isEmpty()).thenReturn(false);

        ItemDependency staffOfAir = ItemDependency.builder()
                .itemId("staff_of_air").itemName("Staff of air")
                .obtainMethod(ObtainMethod.SHOP).build();
        ItemDependency ironPlatebody = ItemDependency.builder()
                .itemId("iron_platebody").itemName("Iron platebody")
                .obtainMethod(ObtainMethod.SHOP).build();
        ItemDependency magicLongbow = ItemDependency.builder()
                .itemId("magic_longbow").itemName("Magic longbow")
                .obtainMethod(ObtainMethod.SHOP).build();
        ItemDependency shark = ItemDependency.builder()
                .itemId("shark").itemName("Shark")
                .obtainMethod(ObtainMethod.SHOP).build();
        ItemDependency prayerPotion = ItemDependency.builder()
                .itemId("prayer_potion").itemName("Prayer potion")
                .obtainMethod(ObtainMethod.SHOP).build();

        ItemDependency airBattlestaff = ItemDependency.builder()
                .itemId("air_battlestaff").itemName("Air battlestaff")
                .obtainMethod(ObtainMethod.SHOP).build();

        // Wire up findLongestMatchingItem for each item
        when(graph.findLongestMatchingItem(anyString())).thenReturn(null);
        when(graph.findLongestMatchingItem(contains("staff of air"))).thenReturn(staffOfAir);
        when(graph.findLongestMatchingItem(contains("air staff"))).thenReturn(staffOfAir);
        when(graph.findLongestMatchingItem(contains("air battlestaff"))).thenReturn(airBattlestaff);
        when(graph.findLongestMatchingItem(contains("iron platebody"))).thenReturn(ironPlatebody);
        when(graph.findLongestMatchingItem(contains("magic longbow"))).thenReturn(magicLongbow);
        when(graph.findLongestMatchingItem(contains("shark"))).thenReturn(shark);
        when(graph.findLongestMatchingItem(contains("prayer potion"))).thenReturn(prayerPotion);
    }

    // -------------------------------------------------------------------------
    // Crafting verb → CRAFT goal
    // -------------------------------------------------------------------------

    @Test
    public void make_staffOfAir_isCraft() {
        GoalSpec spec = GoalSpecParser.parse("make a staff of air", repo, graph);
        assertEquals(GoalType.CRAFT, spec.getType());
        assertEquals("staff_of_air", spec.getTargetId());
    }

    @Test
    public void makeWithContext_staffOfAir_isCraft() {
        GoalSpec spec = GoalSpecParser.parse("now i want to make a staff of air", repo, graph);
        assertEquals(GoalType.CRAFT, spec.getType());
        assertEquals("staff_of_air", spec.getTargetId());
    }

    @Test
    public void make_airStaff_bagOfWords_isCraft() {
        // "air staff" is word-order variant of "staff of air"
        GoalSpec spec = GoalSpecParser.parse("make an air staff", repo, graph);
        assertEquals(GoalType.CRAFT, spec.getType());
    }

    @Test
    public void craft_verb_isCraft() {
        GoalSpec spec = GoalSpecParser.parse("craft an air battlestaff", repo, graph);
        // The graph lookup for "air battlestaff" or "staff of air" — doesn't matter
        // which item matched; what matters is the CRAFT type.
        assertEquals(GoalType.CRAFT, spec.getType());
    }

    @Test
    public void smith_ironPlatebody_isCraft() {
        GoalSpec spec = GoalSpecParser.parse("smith iron platebody", repo, graph);
        assertEquals(GoalType.CRAFT, spec.getType());
        assertEquals("iron_platebody", spec.getTargetId());
    }

    @Test
    public void make_ironPlatebody_isCraft() {
        GoalSpec spec = GoalSpecParser.parse("make iron platebody", repo, graph);
        assertEquals(GoalType.CRAFT, spec.getType());
        assertEquals("iron_platebody", spec.getTargetId());
    }

    @Test
    public void fletch_magicLongbow_isCraft() {
        GoalSpec spec = GoalSpecParser.parse("fletch magic longbow", repo, graph);
        assertEquals(GoalType.CRAFT, spec.getType());
        assertEquals("magic_longbow", spec.getTargetId());
    }

    @Test
    public void make_magicLongbow_isCraft() {
        GoalSpec spec = GoalSpecParser.parse("make magic longbow", repo, graph);
        assertEquals(GoalType.CRAFT, spec.getType());
    }

    @Test
    public void cook_shark_isCraft() {
        GoalSpec spec = GoalSpecParser.parse("cook shark", repo, graph);
        assertEquals(GoalType.CRAFT, spec.getType());
        assertEquals("shark", spec.getTargetId());
    }

    @Test
    public void brew_prayerPotion_isCraft() {
        GoalSpec spec = GoalSpecParser.parse("brew prayer potion", repo, graph);
        assertEquals(GoalType.CRAFT, spec.getType());
        assertEquals("prayer_potion", spec.getTargetId());
    }

    @Test
    public void make_prayerPotion_isCraft() {
        GoalSpec spec = GoalSpecParser.parse("make prayer potion", repo, graph);
        assertEquals(GoalType.CRAFT, spec.getType());
    }

    // -------------------------------------------------------------------------
    // Non-crafting intent → ITEM goal (not CRAFT)
    // -------------------------------------------------------------------------

    @Test
    public void need_staffOfAir_isItem() {
        GoalSpec spec = GoalSpecParser.parse("i need a staff of air", repo, graph);
        assertEquals(GoalType.ITEM, spec.getType());
        assertEquals("staff_of_air", spec.getTargetId());
    }

    @Test
    public void get_staffOfAir_isItem() {
        GoalSpec spec = GoalSpecParser.parse("get a staff of air", repo, graph);
        assertEquals(GoalType.ITEM, spec.getType());
    }

    @Test
    public void want_staffOfAir_isItem() {
        GoalSpec spec = GoalSpecParser.parse("i want a staff of air", repo, graph);
        assertEquals(GoalType.ITEM, spec.getType());
    }

    // -------------------------------------------------------------------------
    // Crafting verb with unknown item → TASK_BATCH (not CRAFT, not ITEM)
    // -------------------------------------------------------------------------

    @Test
    public void make_unknownItem_fallsThrough() {
        when(graph.findLongestMatchingItem(contains("vorpal sword"))).thenReturn(null);
        GoalSpec spec = GoalSpecParser.parse("make a vorpal sword", repo, graph);
        // No known item → falls through to TASK_BATCH
        assertNotEquals(GoalType.CRAFT, spec.getType());
        assertNotEquals(GoalType.ITEM, spec.getType());
        assertEquals(GoalType.TASK_BATCH, spec.getType());
    }

    // -------------------------------------------------------------------------
    // Catalog: verify all CraftableItemsCatalog trigger phrases → CRAFT
    // -------------------------------------------------------------------------

    @Test
    public void allCatalogCraftingTriggers_routeToCraft() {
        // For each item in the catalog, verify the first trigger phrase parses as CRAFT.
        // Wire up the graph to return a stub dep for any phrase containing the item name.
        for (CraftableItemsCatalog.ItemEntry item : CraftableItemsCatalog.ALL) {
            ItemDependency dep = ItemDependency.builder()
                    .itemId(item.getWikiSlug().toLowerCase().replace('-', '_'))
                    .itemName(item.getDisplayName())
                    .obtainMethod(ObtainMethod.SHOP)
                    .build();
            // Stub broadly so any phrase lookup during this iteration returns the item.
            // This handles aliases like "make black dragonhide body" → "Black d'hide body"
            // where the trigger phrase doesn't contain the canonical display name.
            when(graph.findLongestMatchingItem(anyString())).thenReturn(dep);

            String trigger = item.getTriggerPhrases().get(0);
            GoalSpec spec = GoalSpecParser.parse(trigger, repo, graph);
            assertEquals(
                    "Trigger '" + trigger + "' should yield CRAFT for " + item.getDisplayName(),
                    GoalType.CRAFT, spec.getType()
            );
        }
    }
}
