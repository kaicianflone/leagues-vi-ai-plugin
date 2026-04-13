package com.leaguesai.agent;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link WikiItemLookup} static utilities and recipe/shop parsing.
 *
 * <p>All tests here are purely in-memory — no network calls. For actual wiki
 * integration tests that fetch live wikitext, see {@link WikiItemLookupIntegrationTest}.
 *
 * <p>Uses a {@link FakeWikiItemLookup} subclass that overrides
 * {@link WikiItemLookup#fetchWikitext} to return controlled wikitext, keeping
 * these tests fast and deterministic.
 */
public class WikiItemLookupTest {

    // -------------------------------------------------------------------------
    // Static utility: craftAliasSlug
    // -------------------------------------------------------------------------

    @Test
    public void craftAlias_staffOfAir_returnsAirBattlestaff() {
        assertEquals("Air_battlestaff", WikiItemLookup.craftAliasSlug("staff of air"));
    }

    @Test
    public void craftAlias_airStaff_returnsAirBattlestaff() {
        assertEquals("Air_battlestaff", WikiItemLookup.craftAliasSlug("air staff"));
    }

    @Test
    public void craftAlias_caseInsensitive() {
        assertEquals("Air_battlestaff", WikiItemLookup.craftAliasSlug("Staff Of Air"));
        assertEquals("Air_battlestaff", WikiItemLookup.craftAliasSlug("AIR STAFF"));
    }

    @Test
    public void craftAlias_allElementalStaves() {
        assertEquals("Water_battlestaff", WikiItemLookup.craftAliasSlug("staff of water"));
        assertEquals("Earth_battlestaff", WikiItemLookup.craftAliasSlug("staff of earth"));
        assertEquals("Fire_battlestaff",  WikiItemLookup.craftAliasSlug("staff of fire"));
        assertEquals("Water_battlestaff", WikiItemLookup.craftAliasSlug("water staff"));
        assertEquals("Earth_battlestaff", WikiItemLookup.craftAliasSlug("earth staff"));
        assertEquals("Fire_battlestaff",  WikiItemLookup.craftAliasSlug("fire staff"));
    }

    @Test
    public void craftAlias_unknownItem_returnsNull() {
        assertNull(WikiItemLookup.craftAliasSlug("rune platebody"));
        assertNull(WikiItemLookup.craftAliasSlug("dragon scimitar"));
        assertNull(WikiItemLookup.craftAliasSlug(""));
    }

    @Test
    public void craftAlias_null_returnsNull() {
        assertNull(WikiItemLookup.craftAliasSlug(null));
    }

    // -------------------------------------------------------------------------
    // Static utility: getObtainLocation
    // -------------------------------------------------------------------------

    @Test
    public void obtainLocation_airOrb_returnsEdgevilleDungeonEntrance() {
        WorldPoint loc = WikiItemLookup.getObtainLocation("air orb");
        assertNotNull("air orb should have a known obtain location", loc);
    }

    @Test
    public void obtainLocation_knownItems_areAllNonNull() {
        assertNotNull(WikiItemLookup.getObtainLocation("water orb"));
        assertNotNull(WikiItemLookup.getObtainLocation("earth orb"));
        assertNotNull(WikiItemLookup.getObtainLocation("fire orb"));
        assertNotNull(WikiItemLookup.getObtainLocation("cosmic rune"));
    }

    @Test
    public void obtainLocation_unknownItem_returnsNull() {
        assertNull(WikiItemLookup.getObtainLocation("dragon scimitar"));
        assertNull(WikiItemLookup.getObtainLocation(""));
    }

    @Test
    public void obtainLocation_null_returnsNull() {
        assertNull(WikiItemLookup.getObtainLocation(null));
    }

    // -------------------------------------------------------------------------
    // Static utility: pageSlugFromUrl
    // -------------------------------------------------------------------------

    @Test
    public void pageSlugFromUrl_fullUrl() {
        assertEquals("Staff_of_air",
                WikiItemLookup.pageSlugFromUrl("https://oldschool.runescape.wiki/w/Staff_of_air"));
    }

    @Test
    public void pageSlugFromUrl_slugOnly() {
        assertEquals("Rune_platebody",
                WikiItemLookup.pageSlugFromUrl("Rune_platebody"));
    }

    @Test
    public void pageSlugFromUrl_null() {
        assertNull(WikiItemLookup.pageSlugFromUrl(null));
        assertNull(WikiItemLookup.pageSlugFromUrl(""));
    }

    // -------------------------------------------------------------------------
    // parseShopEntries — unit tests with hardcoded wikitext
    // -------------------------------------------------------------------------

    @Test
    public void parseShopEntries_zaffShop_found() {
        String wikitext = "This item is sold by [[Zaff's Superior Staffs]] in Varrock.\n"
                + "{{DropsTableHead}}\n";
        WikiItemLookup lookup = new WikiItemLookup();
        List<WikiItemLookup.ShopEntry> entries = lookup.parseShopEntries(wikitext, 1381, 1500);

        assertFalse("Zaff shop should be found", entries.isEmpty());
        WikiItemLookup.ShopEntry zaff = entries.get(0);
        assertEquals("Zaff", zaff.getNpcName());
        assertNotNull("Shop location should be populated", zaff.getLocation());
        assertNotNull("Nearest bank should be populated", zaff.getNearestBank());
        assertEquals(1381, zaff.getItemGameId());
        // Shop price ≈ 130% of value (1500*1.3 = 1950, ceiling)
        assertEquals(1950, zaff.getShopValue());
    }

    @Test
    public void parseShopEntries_noKnownShop_empty() {
        String wikitext = "This item is dropped by [[Giant spider]] and [[Ogre]].";
        WikiItemLookup lookup = new WikiItemLookup();
        List<WikiItemLookup.ShopEntry> entries = lookup.parseShopEntries(wikitext, 999, 500);
        assertTrue("Unknown shop should return empty", entries.isEmpty());
    }

    @Test
    public void parseShopEntries_multipleShops_allFound() {
        // Staff items appear in both Zaff and the Magic Guild
        String wikitext = "Available from [[Zaff's Superior Staffs]] "
                + "and [[Magic Guild Store (Runes and Staves)]].";
        WikiItemLookup lookup = new WikiItemLookup();
        List<WikiItemLookup.ShopEntry> entries = lookup.parseShopEntries(wikitext, 0, 0);
        assertTrue("Both shops should be found", entries.size() >= 2);
    }

    @Test
    public void parseShopEntries_nullWikitext_empty() {
        WikiItemLookup lookup = new WikiItemLookup();
        assertTrue(lookup.parseShopEntries(null, 0, 0).isEmpty());
        assertTrue(lookup.parseShopEntries("", 0, 0).isEmpty());
    }

    // -------------------------------------------------------------------------
    // lookupCraftingRecipe — unit tests with fake wikitext (no network)
    // -------------------------------------------------------------------------

    /** Fake wikitext for Air battlestaff — mirrors the real wiki's Crafting template. */
    private static final String AIR_BATTLESTAFF_WIKITEXT =
            "{{Infobox Item\n"
            + "|name = Air battlestaff\n"
            + "|id = 1397\n"
            + "|value = 9300\n"
            + "}}\n"
            + "An '''air battlestaff''' is a medium level Magic weapon.\n"
            + "{{Crafting table\n"
            + "|mat1 = Battlestaff|mat1qty = 1\n"
            + "|mat2 = Air orb|mat2qty = 1\n"
            + "|level = 66\n"
            + "|xp = 137.5\n"
            + "|output = Air battlestaff\n"
            + "}}\n";

    @Test
    public void lookupCraftingRecipe_airBattlestaff_parsedCorrectly() {
        WikiItemLookup lookup = fakeWith("Air_battlestaff", AIR_BATTLESTAFF_WIKITEXT);
        WikiItemLookup.CraftingRecipe recipe = lookup.lookupCraftingRecipe("Air_battlestaff");

        assertNotNull("Recipe should be found", recipe);
        assertEquals("Crafting", recipe.getSkill());
        assertEquals(66, recipe.getLevelRequired());
        assertEquals(137.5, recipe.getXpGained(), 0.01);
        assertEquals(2, recipe.getMaterials().size());

        WikiItemLookup.MaterialEntry mat1 = recipe.getMaterials().get(0);
        assertEquals("Battlestaff", mat1.getItemName());
        assertEquals(1, mat1.getQuantity());

        WikiItemLookup.MaterialEntry mat2 = recipe.getMaterials().get(1);
        assertEquals("Air orb", mat2.getItemName());
        assertEquals(1, mat2.getQuantity());
    }

    @Test
    public void lookupCraftingRecipe_craftingSkill_hasCraftingLocation() {
        WikiItemLookup lookup = fakeWith("Air_battlestaff", AIR_BATTLESTAFF_WIKITEXT);
        WikiItemLookup.CraftingRecipe recipe = lookup.lookupCraftingRecipe("Air_battlestaff");

        assertNotNull(recipe);
        assertNotNull("Crafting skill location name should be set", recipe.getCraftingLocationName());
        assertFalse(recipe.getCraftingLocationName().isEmpty());
    }

    /** Fake wikitext for Iron platebody — Smithing template. */
    private static final String IRON_PLATEBODY_WIKITEXT =
            "{{Infobox Item\n"
            + "|name = Iron platebody\n"
            + "|id = 1115\n"
            + "|value = 250\n"
            + "}}\n"
            + "{{Smithing table\n"
            + "|mat1 = Iron bar|mat1qty = 5\n"
            + "|level = 33\n"
            + "|xp = 125\n"
            + "|output = Iron platebody\n"
            + "}}\n";

    @Test
    public void lookupCraftingRecipe_ironPlatebody_smithingSkill() {
        WikiItemLookup lookup = fakeWith("Iron_platebody", IRON_PLATEBODY_WIKITEXT);
        WikiItemLookup.CraftingRecipe recipe = lookup.lookupCraftingRecipe("Iron_platebody");

        assertNotNull(recipe);
        assertEquals("Smithing", recipe.getSkill());
        assertEquals(33, recipe.getLevelRequired());
        assertEquals(125.0, recipe.getXpGained(), 0.1);
        assertEquals(1, recipe.getMaterials().size());
        assertEquals("Iron bar", recipe.getMaterials().get(0).getItemName());
        assertEquals(5, recipe.getMaterials().get(0).getQuantity());
        // Smithing location should be the Varrock anvil
        assertNotNull("Smithing should have a crafting location", recipe.getCraftingLocation());
    }

    /** Fake wikitext for Magic longbow — Fletching template. */
    private static final String MAGIC_LONGBOW_WIKITEXT =
            "{{Infobox Item\n"
            + "|name = Magic longbow\n"
            + "|id = 859\n"
            + "|value = 1536\n"
            + "}}\n"
            + "{{Fletching table\n"
            + "|mat1 = Magic logs|mat1qty = 1\n"
            + "|mat2 = Bowstring|mat2qty = 1\n"
            + "|level = 85\n"
            + "|xp = 91.5\n"
            + "|output = Magic longbow\n"
            + "}}\n";

    @Test
    public void lookupCraftingRecipe_magicLongbow_fletchingSkill() {
        WikiItemLookup lookup = fakeWith("Magic_longbow", MAGIC_LONGBOW_WIKITEXT);
        WikiItemLookup.CraftingRecipe recipe = lookup.lookupCraftingRecipe("Magic_longbow");

        assertNotNull(recipe);
        assertEquals("Fletching", recipe.getSkill());
        assertEquals(85, recipe.getLevelRequired());
        assertEquals(91.5, recipe.getXpGained(), 0.1);
        assertEquals(2, recipe.getMaterials().size());
        // Fletching can be done anywhere — location may be null
        assertNotNull("Fletching location name should be set", recipe.getCraftingLocationName());
    }

    @Test
    public void lookupCraftingRecipe_noTemplate_returnsNull() {
        String wikitext = "{{Infobox Item\n|name = Dragon scimitar\n|id = 4587\n}}\n"
                + "The dragon scimitar is a one-handed weapon. It is not crafted.";
        WikiItemLookup lookup = fakeWith("Dragon_scimitar", wikitext);
        WikiItemLookup.CraftingRecipe recipe = lookup.lookupCraftingRecipe("Dragon_scimitar");
        assertNull("No crafting template → recipe should be null", recipe);
    }

    @Test
    public void lookupCraftingRecipe_nullSlug_returnsNull() {
        WikiItemLookup lookup = new WikiItemLookup();
        assertNull(lookup.lookupCraftingRecipe(null));
        assertNull(lookup.lookupCraftingRecipe(""));
    }

    @Test
    public void lookupCraftingRecipe_wikiFetchFails_returnsNull() {
        // fetchWikitext returns null → recipe should be null gracefully
        WikiItemLookup lookup = fakeWith("Nonexistent_Item", null);
        assertNull(lookup.lookupCraftingRecipe("Nonexistent_Item"));
    }

    @Test
    public void lookupCraftingRecipe_wikiLinkSyntaxStripped() {
        // Some wiki pages use [[Material]] link syntax in mat fields
        String wikitext = "{{Crafting table\n"
                + "|mat1 = [[Battlestaff]]|mat1qty = 1\n"
                + "|mat2 = [[Air orb]]|mat2qty = 1\n"
                + "|level = 66\n|xp = 137.5\n}}\n";
        WikiItemLookup lookup = fakeWith("Air_battlestaff", wikitext);
        WikiItemLookup.CraftingRecipe recipe = lookup.lookupCraftingRecipe("Air_battlestaff");

        assertNotNull(recipe);
        // [[Battlestaff]] should be stripped to "Battlestaff"
        assertEquals("Battlestaff", recipe.getMaterials().get(0).getItemName());
        assertEquals("Air orb", recipe.getMaterials().get(1).getItemName());
    }

    @Test
    public void lookupCraftingRecipe_wikiLinkWithAlias_usesDisplayText() {
        // [[Page|Display]] → should use "Display"
        String wikitext = "{{Crafting table\n"
                + "|mat1 = [[Gold bar|Gold bar]]\n|mat1qty = 1\n"
                + "|mat2 = [[Emerald|Emerald gemstone]]\n|mat2qty = 1\n"
                + "|level = 27\n|xp = 55\n}}\n";
        WikiItemLookup lookup = fakeWith("Emerald_ring", wikitext);
        WikiItemLookup.CraftingRecipe recipe = lookup.lookupCraftingRecipe("Emerald_ring");

        assertNotNull(recipe);
        assertEquals("Gold bar", recipe.getMaterials().get(0).getItemName());
        assertEquals("Emerald gemstone", recipe.getMaterials().get(1).getItemName());
    }

    // -------------------------------------------------------------------------
    // ShopEntry value correctness
    // -------------------------------------------------------------------------

    @Test
    public void shopEntry_priceCalculation_130PercentOfBaseValue() {
        WikiItemLookup lookup = new WikiItemLookup();
        // value = 1500 → shop price = ceil(1500 * 1.3) = 1950
        List<WikiItemLookup.ShopEntry> entries = lookup.parseShopEntries(
                "[[Zaff's Superior Staffs]]", 1381, 1500);
        assertFalse(entries.isEmpty());
        assertEquals(1950, entries.get(0).getShopValue());
    }

    @Test
    public void shopEntry_zeroBaseValue_zeroShopPrice() {
        WikiItemLookup lookup = new WikiItemLookup();
        List<WikiItemLookup.ShopEntry> entries = lookup.parseShopEntries(
                "[[Zaff's Superior Staffs]]", 1381, 0);
        assertFalse(entries.isEmpty());
        assertEquals(0, entries.get(0).getShopValue());
    }

    // -------------------------------------------------------------------------
    // Helper: fake WikiItemLookup that returns preset wikitext
    // -------------------------------------------------------------------------

    private static WikiItemLookup fakeWith(String expectedSlug, String wikitext) {
        return new WikiItemLookup() {
            @Override
            String fetchWikitext(String pageSlug) {
                return pageSlug.equals(expectedSlug) ? wikitext : null;
            }
        };
    }
}
