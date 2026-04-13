package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.overlay.OverlayData;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end tests for {@link GoalPlanner#resolveCraftGoal} (CRAFT goal type).
 *
 * <p>All wiki calls are intercepted by a {@link FakeWikiItemLookup} — no network.
 * Tests cover:
 * <ul>
 *   <li>Happy path: recipe found → correct number of steps built</li>
 *   <li>Craft alias resolution (staff of air → Air_battlestaff)</li>
 *   <li>Shop material → bank + buy steps</li>
 *   <li>Non-shop material → obtain-location arrow step</li>
 *   <li>Final crafting step always added</li>
 *   <li>Inventory pre-checks: skip bank step (coins held), skip material step (held),
 *       skip whole plan (item already crafted)</li>
 *   <li>Null wikiItemLookup → graceful empty plan</li>
 *   <li>No crafting recipe found → graceful empty plan (LLM fallback)</li>
 *   <li>All CraftableItemsCatalog items produce at least 1 step</li>
 * </ul>
 */
public class GoalPlannerCraftTest {

    private static final String AIR_BATTLESTAFF_WIKITEXT =
            "{{Infobox Item\n|name = Air battlestaff\n|id = 1397\n|value = 9300\n}}\n"
            + "{{Crafting table\n"
            + "|mat1 = Battlestaff|mat1qty = 1\n"
            + "|mat2 = Air orb|mat2qty = 1\n"
            + "|level = 66\n|xp = 137.5\n"
            + "|output = Air battlestaff\n"
            + "}}\n";

    private static final String BATTLESTAFF_WIKITEXT =
            "{{Infobox Item\n|name = Battlestaff\n|id = 1391\n|value = 7000\n}}\n"
            + "Sold by [[Zaff's Superior Staffs]] in Varrock.\n";

    private static final String IRON_PLATEBODY_WIKITEXT =
            "{{Infobox Item\n|name = Iron platebody\n|id = 1115\n|value = 250\n}}\n"
            + "{{Smithing table\n"
            + "|mat1 = Iron bar|mat1qty = 5\n"
            + "|level = 33\n|xp = 125\n"
            + "|output = Iron platebody\n"
            + "}}\n";

    private static final String IRON_BAR_WIKITEXT =
            "{{Infobox Item\n|name = Iron bar\n|id = 2351\n|value = 56\n}}\n"
            + "Obtained by smelting iron ore in a furnace. Not sold in standard shops.\n";

    private GoalPlanner planner;
    private FakeWikiItemLookup fakeWiki;
    private TaskRepository taskRepo;
    private ItemDependencyGraph graph;

    @Before
    public void setUp() {
        taskRepo = mock(TaskRepository.class);
        when(taskRepo.getAllTasks()).thenReturn(Collections.emptyList());
        when(taskRepo.getAllAreas()).thenReturn(Collections.emptyList());
        when(taskRepo.getPrerequisites(anyString())).thenReturn(Collections.emptyList());

        graph = mock(ItemDependencyGraph.class);
        when(graph.isEmpty()).thenReturn(false);
        when(graph.isInCatalog(anyString())).thenReturn(false);

        fakeWiki = new FakeWikiItemLookup();
        fakeWiki.register("Air_battlestaff", AIR_BATTLESTAFF_WIKITEXT);
        fakeWiki.register("Battlestaff",      BATTLESTAFF_WIKITEXT);
        fakeWiki.register("Iron_platebody",   IRON_PLATEBODY_WIKITEXT);
        fakeWiki.register("Iron_bar",         IRON_BAR_WIKITEXT);

        planner = new GoalPlanner(taskRepo, graph);
        planner.setWikiItemLookup(fakeWiki);
    }

    // -------------------------------------------------------------------------
    // Happy path: Air battlestaff (Crafting)
    // -------------------------------------------------------------------------

    @Test
    public void airBattlestaff_craftGoal_buildsSteps() {
        GoalSpec spec = craftSpec("staff_of_air", "Staff of air");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, emptyCtx());

        assertNotNull(composite);
        assertTrue(composite.isReachable());
        // Expected steps:
        //  1. Bank (coins for Battlestaff)
        //  2. Buy Battlestaff from Zaff
        //  3. Obtain Air orb (no shop → obtain-location arrow)
        //  4. Craft Air battlestaff
        // (bank step may be skipped if wikitext price is 0 — depends on fake value)
        assertFalse("directSteps should not be empty", composite.getDirectSteps().isEmpty());
    }

    @Test
    public void airBattlestaff_lastStep_isCraftInstruction() {
        GoalSpec spec = craftSpec("staff_of_air", "Staff of air");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, emptyCtx());

        List<PlannedStep> steps = composite.getDirectSteps();
        PlannedStep last = steps.get(steps.size() - 1);
        assertTrue("Last step should describe crafting",
                last.getInstruction().toLowerCase().contains("craft"));
        assertTrue("Last step should name the crafted item",
                last.getInstruction().contains("Air battlestaff"));
    }

    @Test
    public void airBattlestaff_shopMaterial_hasBankAndBuySteps() {
        // Battlestaff is in Zaff's shop — should get a bank step + buy step
        GoalSpec spec = craftSpec("staff_of_air", "Staff of air");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, emptyCtx());

        List<PlannedStep> steps = composite.getDirectSteps();
        // Find steps that mention Zaff / Battlestaff
        boolean hasBuyStep = steps.stream().anyMatch(s ->
                s.getInstruction().toLowerCase().contains("battlestaff"));
        assertTrue("Should have a step to acquire Battlestaff", hasBuyStep);
    }

    @Test
    public void airBattlestaff_airOrb_hasObtainStep() {
        // Air orb is not in a shop → should get an "Obtain" step with a location arrow
        GoalSpec spec = craftSpec("staff_of_air", "Staff of air");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, emptyCtx());

        boolean hasOrbStep = composite.getDirectSteps().stream().anyMatch(s ->
                s.getInstruction().toLowerCase().contains("air orb"));
        assertTrue("Should have a step to obtain Air orb", hasOrbStep);
    }

    @Test
    public void airBattlestaff_airOrb_hasOverlayArrow() {
        GoalSpec spec = craftSpec("staff_of_air", "Staff of air");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, emptyCtx());

        PlannedStep orbStep = composite.getDirectSteps().stream()
                .filter(s -> s.getInstruction().toLowerCase().contains("air orb"))
                .findFirst().orElse(null);
        assertNotNull(orbStep);
        OverlayData overlay = orbStep.getOverlayData();
        assertNotNull(overlay);
        // The air orb obtain location is known → arrow should be shown
        assertTrue(overlay.isShowArrow());
    }

    // -------------------------------------------------------------------------
    // Smithing: Iron platebody
    // -------------------------------------------------------------------------

    @Test
    public void ironPlatebody_craftGoal_buildsSteps() {
        GoalSpec spec = craftSpec("iron_platebody", "Iron platebody");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, emptyCtx());

        assertNotNull(composite);
        assertFalse("directSteps should not be empty", composite.getDirectSteps().isEmpty());
    }

    @Test
    public void ironPlatebody_ironBar_noShop_hasObtainStep() {
        // Iron bar is NOT in a known shop → obtain-location step
        GoalSpec spec = craftSpec("iron_platebody", "Iron platebody");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, emptyCtx());

        boolean hasBarStep = composite.getDirectSteps().stream().anyMatch(s ->
                s.getInstruction().toLowerCase().contains("iron bar"));
        assertTrue("Should have a step mentioning Iron bar", hasBarStep);
    }

    @Test
    public void ironPlatebody_lastStep_isSmithingCraft() {
        GoalSpec spec = craftSpec("iron_platebody", "Iron platebody");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, emptyCtx());

        PlannedStep last = composite.getDirectSteps().get(composite.getDirectSteps().size() - 1);
        assertTrue(last.getInstruction().toLowerCase().contains("craft"));
        assertTrue(last.getInstruction().toLowerCase().contains("smithing"));
    }

    // -------------------------------------------------------------------------
    // Inventory pre-checks
    // -------------------------------------------------------------------------

    @Test
    public void inventoryCheck_alreadyHasCraftedItem_skipsPlan() {
        // If the player already has "Air battlestaff", the whole plan should be skipped.
        PlayerContext ctx = contextWithInventory(Collections.singletonMap("Air battlestaff", 1));
        GoalSpec spec = craftSpec("staff_of_air", "Staff of air");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);

        assertNotNull(composite);
        assertTrue("Plan should be empty when item already held",
                composite.getDirectSteps().isEmpty());
    }

    @Test
    public void inventoryCheck_hasMaterial_materialStepSkipped() {
        // If the player already has a Battlestaff, that material step should be skipped.
        PlayerContext ctx = contextWithInventory(Collections.singletonMap("Battlestaff", 1));
        GoalSpec spec = craftSpec("staff_of_air", "Staff of air");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);

        boolean hasBattlestaffStep = composite.getDirectSteps().stream().anyMatch(s ->
                s.getInstruction().toLowerCase().contains("battlestaff")
                && !s.getInstruction().toLowerCase().contains("craft"));
        assertFalse("Battlestaff step should be skipped when already held", hasBattlestaffStep);
    }

    @Test
    public void inventoryCheck_hasEnoughCoins_bankStepSkipped() {
        // Battlestaff costs ~9100 coins (7000 * 1.3). If player has 10000, skip bank step.
        Map<String, Integer> inv = new HashMap<>();
        inv.put("Coins", 10000);
        PlayerContext ctx = contextWithInventory(inv);
        GoalSpec spec = craftSpec("staff_of_air", "Staff of air");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);

        boolean hasBankStep = composite.getDirectSteps().stream().anyMatch(s ->
                s.getInstruction().toLowerCase().contains("withdraw coins"));
        assertFalse("Bank step should be skipped when player has enough coins", hasBankStep);
    }

    @Test
    public void inventoryCheck_insufficientCoins_bankStepPresent() {
        Map<String, Integer> inv = new HashMap<>();
        inv.put("Coins", 100); // Not enough
        PlayerContext ctx = contextWithInventory(inv);
        GoalSpec spec = craftSpec("staff_of_air", "Staff of air");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, ctx);

        boolean hasBankStep = composite.getDirectSteps().stream().anyMatch(s ->
                s.getInstruction().toLowerCase().contains("withdraw coins"));
        assertTrue("Bank step should be present when player has insufficient coins", hasBankStep);
    }

    // -------------------------------------------------------------------------
    // Null / missing wikiItemLookup
    // -------------------------------------------------------------------------

    @Test
    public void nullWikiLookup_returnsEmptyPlan() {
        planner.setWikiItemLookup(null);
        GoalSpec spec = craftSpec("staff_of_air", "Staff of air");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, emptyCtx());

        assertNotNull(composite);
        assertTrue("No wiki lookup → empty plan", composite.getDirectSteps().isEmpty());
        assertTrue(composite.isReachable()); // graceful fallback, not an error
    }

    @Test
    public void noRecipeFound_returnsEmptyPlan() {
        // Item page exists but has no crafting template → LLM fallback
        fakeWiki.register("Dragon_scimitar",
                "{{Infobox Item\n|name=Dragon scimitar\n}}\nNot craftable.");
        GoalSpec spec = craftSpec("dragon_scimitar", "Dragon scimitar");
        CompositeGoal composite = planner.resolveCompositeGoal(spec, emptyCtx());

        assertNotNull(composite);
        assertTrue("No recipe → empty directSteps", composite.getDirectSteps().isEmpty());
        assertTrue(composite.isReachable());
    }

    // -------------------------------------------------------------------------
    // Craft alias resolution
    // -------------------------------------------------------------------------

    @Test
    public void craftAlias_staffOfAir_fetchesAirBattlestaffPage() {
        // Spec has targetName "Staff of air" — alias table maps it to Air_battlestaff slug.
        GoalSpec spec = craftSpec("staff_of_air", "Staff of air");
        planner.resolveCompositeGoal(spec, emptyCtx());

        // Verify the wiki was asked for "Air_battlestaff", not "Staff_of_air"
        assertTrue("Should have fetched Air_battlestaff page",
                fakeWiki.wasFetched("Air_battlestaff"));
        assertFalse("Should NOT have fetched Staff_of_air page",
                fakeWiki.wasFetched("Staff_of_air"));
    }

    // -------------------------------------------------------------------------
    // CraftableItemsCatalog: every item produces at least 1 step
    // -------------------------------------------------------------------------

    @Test
    public void allCatalogItems_produceAtLeastOneStep() {
        // For each item, register fake wikitext with a minimal Crafting template
        // and verify the planner builds at least 1 directStep.
        for (CraftableItemsCatalog.ItemEntry item : CraftableItemsCatalog.ALL) {
            String slug = item.getWikiSlug();
            String fakeWikitext = buildFakeWikitext(item);
            fakeWiki.register(slug, fakeWikitext);
            // GoalPlanner derives the slug from targetName.replace(' ', '_'), which uses
            // the literal apostrophe — not the %27 URL encoding in catalog wikiSlugs.
            String plainSlug = item.getDisplayName().replace(' ', '_');
            if (!plainSlug.equals(slug)) {
                fakeWiki.register(plainSlug, fakeWikitext);
            }

            // Also register each material as "no shop" so we get obtain steps
            for (String mat : item.getMaterialNames()) {
                fakeWiki.register(mat.replace(' ', '_'),
                        "{{Infobox Item\n|name=" + mat + "\n}}\nNot in a known shop.");
            }

            String id = slug.toLowerCase().replace('-', '_');
            GoalSpec spec = craftSpec(id, item.getDisplayName());
            CompositeGoal composite = planner.resolveCompositeGoal(spec, emptyCtx());

            assertNotNull("Composite should not be null for " + item.getDisplayName(), composite);
            assertFalse(
                "'" + item.getDisplayName() + "' should produce at least 1 step",
                composite.getDirectSteps().isEmpty()
            );
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GoalSpec craftSpec(String itemId, String itemName) {
        return GoalSpec.builder()
                .type(GoalType.CRAFT)
                .targetId(itemId)
                .targetName(itemName)
                .rawPhrase("make a " + itemName)
                .unlockCost(0)
                .build();
    }

    private PlayerContext emptyCtx() {
        return PlayerContext.builder()
                .inventory(Collections.emptyMap())
                .equipment(Collections.emptyMap())
                .levels(Collections.emptyMap())
                .xp(Collections.emptyMap())
                .completedTasks(Collections.emptySet())
                .unlockedAreas(Collections.emptySet())
                .leaguePoints(0)
                .combatLevel(3)
                .leaguesMode(false)
                .build();
    }

    private PlayerContext contextWithInventory(Map<String, Integer> inventory) {
        return PlayerContext.builder()
                .inventory(inventory)
                .equipment(Collections.emptyMap())
                .levels(Collections.emptyMap())
                .xp(Collections.emptyMap())
                .completedTasks(Collections.emptySet())
                .unlockedAreas(Collections.emptySet())
                .leaguePoints(0)
                .combatLevel(3)
                .leaguesMode(false)
                .build();
    }

    /** Build minimal fake wikitext for a CraftableItemsCatalog entry. */
    private String buildFakeWikitext(CraftableItemsCatalog.ItemEntry item) {
        StringBuilder sb = new StringBuilder();
        sb.append("{{Infobox Item\n|name=").append(item.getDisplayName()).append("\n}}\n");
        sb.append("{{").append(item.getSkill()).append(" table\n");
        for (int i = 0; i < item.getMaterialNames().size(); i++) {
            int matNum = i + 1;
            sb.append("|mat").append(matNum).append(" = ").append(item.getMaterialNames().get(i));
            sb.append("|mat").append(matNum).append("qty = ").append(item.getMaterialQtys().get(i));
            sb.append("\n");
        }
        sb.append("|level = ").append(item.getLevelRequired()).append("\n");
        sb.append("|xp = ").append(item.getXpGained()).append("\n");
        sb.append("|output = ").append(item.getDisplayName()).append("\n");
        sb.append("}}\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // FakeWikiItemLookup inner helper
    // -------------------------------------------------------------------------

    static class FakeWikiItemLookup extends WikiItemLookup {
        private final Map<String, String> wikitext = new HashMap<>();
        private final java.util.Set<String> fetched = new java.util.HashSet<>();

        void register(String slug, String text) {
            wikitext.put(slug, text);
        }

        boolean wasFetched(String slug) {
            return fetched.contains(slug);
        }

        @Override
        String fetchWikitext(String pageSlug) {
            fetched.add(pageSlug);
            return wikitext.get(pageSlug);
        }
    }
}
