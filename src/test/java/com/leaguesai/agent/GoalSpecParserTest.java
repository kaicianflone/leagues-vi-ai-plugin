package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.ItemDependency;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GoalSpecParserTest {

    private static TaskRepository mockRepoWithUnlockables() {
        TaskRepository repo = mock(TaskRepository.class);

        Relic grimoire = Relic.builder()
                .id("grimoire")
                .name("Grimoire")
                .tier(1)
                .unlockCost(200)
                .build();
        Area kourend = Area.builder()
                .id("Kourend")
                .name("Kourend")
                .unlockCost(300)
                .build();
        Pact naturesCall = Pact.builder()
                .id("A1")
                .name("Nature's Call")
                .effect("Nature runes regenerate")
                .build();

        // Stub the match-any fallback FIRST so the specific stubs below
        // override it for known names. Mockito applies the last matching
        // stubbing, so order matters here.
        when(repo.findRelicByName(anyString())).thenReturn(Optional.empty());
        when(repo.findRelicByName("Grimoire")).thenReturn(Optional.of(grimoire));
        when(repo.findRelicByName("grimoire")).thenReturn(Optional.of(grimoire));

        when(repo.findAreaByName(anyString())).thenReturn(Optional.empty());
        when(repo.findAreaByName("Kourend")).thenReturn(Optional.of(kourend));
        when(repo.findAreaByName("kourend")).thenReturn(Optional.of(kourend));

        when(repo.findPactByName(anyString())).thenReturn(Optional.empty());
        when(repo.findPactByName("Nature's Call")).thenReturn(Optional.of(naturesCall));
        when(repo.findPactByName("nature's call")).thenReturn(Optional.of(naturesCall));

        return repo;
    }

    @Test
    public void relicPhraseResolvesToRelicGoal() {
        TaskRepository repo = mockRepoWithUnlockables();
        GoalSpec spec = GoalSpecParser.parse("plan unlock the Grimoire relic", repo);

        assertEquals(GoalType.RELIC, spec.getType());
        assertEquals("grimoire", spec.getTargetId());
        assertEquals("Grimoire", spec.getTargetName());
        assertEquals(200, spec.getUnlockCost());
        assertEquals("plan unlock the Grimoire relic", spec.getRawPhrase());
    }

    @Test
    public void relicPhraseWithoutThe() {
        TaskRepository repo = mockRepoWithUnlockables();
        GoalSpec spec = GoalSpecParser.parse("plan unlock Grimoire relic", repo);
        assertEquals(GoalType.RELIC, spec.getType());
        assertEquals("grimoire", spec.getTargetId());
    }

    @Test
    public void relicPhraseLowercase() {
        TaskRepository repo = mockRepoWithUnlockables();
        GoalSpec spec = GoalSpecParser.parse("plan unlock the grimoire relic", repo);
        assertEquals(GoalType.RELIC, spec.getType());
        assertEquals("grimoire", spec.getTargetId());
    }

    @Test
    public void areaPhraseResolvesToAreaGoal() {
        TaskRepository repo = mockRepoWithUnlockables();
        GoalSpec spec = GoalSpecParser.parse("plan unlock Kourend", repo);

        assertEquals(GoalType.AREA, spec.getType());
        assertEquals("Kourend", spec.getTargetId());
        assertEquals(300, spec.getUnlockCost());
    }

    @Test
    public void pactPhraseResolvesToPactGoal() {
        TaskRepository repo = mockRepoWithUnlockables();
        GoalSpec spec = GoalSpecParser.parse("plan unlock pact Nature's Call", repo);

        assertEquals(GoalType.PACT, spec.getType());
        assertEquals("A1", spec.getTargetId());
        assertEquals("Nature's Call", spec.getTargetName());
        assertEquals(0, spec.getUnlockCost());
    }

    @Test
    public void unknownRelicFallsThroughToTaskBatch() {
        // Parser recognises the shape but the repo has no matching relic —
        // fall through to TASK_BATCH so the existing flat resolver can still
        // try keyword matching.
        TaskRepository repo = mockRepoWithUnlockables();
        GoalSpec spec = GoalSpecParser.parse("plan unlock the Dragon relic", repo);
        assertEquals(GoalType.TASK_BATCH, spec.getType());
        assertNull(spec.getTargetId());
    }

    @Test
    public void nonPlanPhraseIsTaskBatch() {
        // "complete all karamja easy" has no "plan unlock ..." shape at all,
        // so parser falls through to TASK_BATCH. ChatService's existing
        // trigger detection still catches it via phrase list.
        TaskRepository repo = mockRepoWithUnlockables();
        GoalSpec spec = GoalSpecParser.parse("complete all karamja easy", repo);
        assertEquals(GoalType.TASK_BATCH, spec.getType());
    }

    @Test
    public void emptyPhraseIsFreeform() {
        TaskRepository repo = mockRepoWithUnlockables();
        assertEquals(GoalType.FREEFORM, GoalSpecParser.parse("", repo).getType());
        assertEquals(GoalType.FREEFORM, GoalSpecParser.parse(null, repo).getType());
    }

    @Test
    public void nullRepoFallsThroughWithoutCrashing() {
        GoalSpec spec = GoalSpecParser.parse("plan unlock the Grimoire relic", null);
        // Null repo means no lookup possible — still parses the shape but
        // can't resolve a targetId, so falls through to task batch.
        assertEquals(GoalType.TASK_BATCH, spec.getType());
    }

    // -------------------------------------------------------------------------
    // Fuzzy scan arms (getAllRelics / getAllPacts / getAllAreas)
    // -------------------------------------------------------------------------

    @Test
    public void fuzzy_relic_naturalLanguage() {
        TaskRepository repo = mock(TaskRepository.class);
        Relic grimoire = Relic.builder().id("grimoire").name("Grimoire").tier(1).unlockCost(200).build();
        when(repo.getAllRelics()).thenReturn(Collections.singletonList(grimoire));
        when(repo.findRelicByName(anyString())).thenReturn(Optional.empty());

        GoalSpec spec = GoalSpecParser.parse("I want to unlock the Grimoire relic", repo);
        assertEquals(GoalType.RELIC, spec.getType());
        assertEquals("grimoire", spec.getTargetId());
    }

    @Test
    public void fuzzy_relic_no_match_falls_through() {
        TaskRepository repo = mock(TaskRepository.class);
        Relic grimoire = Relic.builder().id("grimoire").name("Grimoire").tier(1).unlockCost(200).build();
        when(repo.getAllRelics()).thenReturn(Collections.singletonList(grimoire));
        when(repo.findRelicByName(anyString())).thenReturn(Optional.empty());

        // "relic" keyword present but no known relic name in phrase
        GoalSpec spec = GoalSpecParser.parse("what is a relic worth", repo);
        assertEquals(GoalType.TASK_BATCH, spec.getType());
    }

    @Test
    public void fuzzy_relic_null_list_does_not_throw() {
        TaskRepository repo = mock(TaskRepository.class);
        when(repo.getAllRelics()).thenReturn(null);
        when(repo.findRelicByName(anyString())).thenReturn(Optional.empty());

        GoalSpec spec = GoalSpecParser.parse("unlock some relic please", repo);
        assertEquals(GoalType.TASK_BATCH, spec.getType());
    }

    @Test
    public void fuzzy_pact_naturalLanguage() {
        TaskRepository repo = mock(TaskRepository.class);
        Pact naturesCall = Pact.builder().id("A1").name("Nature's Call").effect("x").build();
        when(repo.getAllPacts()).thenReturn(Collections.singletonList(naturesCall));
        when(repo.findPactByName(anyString())).thenReturn(Optional.empty());

        GoalSpec spec = GoalSpecParser.parse("select pact Nature's Call for my build", repo);
        assertEquals(GoalType.PACT, spec.getType());
        assertEquals("A1", spec.getTargetId());
    }

    @Test
    public void fuzzy_area_naturalLanguage() {
        TaskRepository repo = mock(TaskRepository.class);
        Area kourend = Area.builder().id("Kourend").name("Kourend").unlockCost(300).build();
        when(repo.getAllAreas()).thenReturn(Collections.singletonList(kourend));
        when(repo.findAreaByName(anyString())).thenReturn(Optional.empty());

        GoalSpec spec = GoalSpecParser.parse("I want to unlock Kourend area", repo);
        assertEquals(GoalType.AREA, spec.getType());
        assertEquals("Kourend", spec.getTargetId());
    }

    // -------------------------------------------------------------------------
    // Item detection arm (3-arg overload with ItemDependencyGraph)
    // -------------------------------------------------------------------------

    private static ItemDependencyGraph mockItemGraph(String itemName, String itemId) {
        ItemDependencyGraph graph = mock(ItemDependencyGraph.class);
        when(graph.isEmpty()).thenReturn(false);
        when(graph.knownNames()).thenReturn(new HashSet<>(Collections.singletonList(itemName)));
        ItemDependency dep = ItemDependency.builder()
                .itemId(itemId)
                .itemName(itemName)
                .obtainMethod(ObtainMethod.DROPPED)
                .build();
        when(graph.findItemByName(itemName)).thenReturn(dep);
        // GoalSpecParser now routes through findLongestMatchingItem; stub it for any
        // phrase that contains the item name.
        when(graph.findLongestMatchingItem(org.mockito.ArgumentMatchers.contains(itemName)))
                .thenReturn(dep);
        return graph;
    }

    @Test
    public void item_detection_get_keyword() {
        TaskRepository repo = mockRepoWithUnlockables();
        ItemDependencyGraph graph = mockItemGraph("rune platebody", "rune_platebody");

        GoalSpec spec = GoalSpecParser.parse("get rune platebody for my build", repo, graph);
        assertEquals(GoalType.ITEM, spec.getType());
        assertEquals("rune_platebody", spec.getTargetId());
        assertEquals("rune platebody", spec.getTargetName());
    }

    @Test
    public void item_detection_need_keyword() {
        TaskRepository repo = mockRepoWithUnlockables();
        ItemDependencyGraph graph = mockItemGraph("dragon scimitar", "dragon_scimitar");

        GoalSpec spec = GoalSpecParser.parse("i need dragon scimitar", repo, graph);
        assertEquals(GoalType.ITEM, spec.getType());
        assertEquals("dragon_scimitar", spec.getTargetId());
    }

    @Test
    public void item_detection_no_keyword_falls_through() {
        TaskRepository repo = mockRepoWithUnlockables();
        ItemDependencyGraph graph = mockItemGraph("dragon scimitar", "dragon_scimitar");

        // No "get"/"need"/etc. keyword — should NOT fire item arm
        GoalSpec spec = GoalSpecParser.parse("dragon scimitar is a cool weapon", repo, graph);
        assertNotEquals(GoalType.ITEM, spec.getType());
    }

    @Test
    public void item_detection_empty_graph_skipped() {
        TaskRepository repo = mockRepoWithUnlockables();
        ItemDependencyGraph graph = mock(ItemDependencyGraph.class);
        when(graph.isEmpty()).thenReturn(true);

        GoalSpec spec = GoalSpecParser.parse("get dragon scimitar", repo, graph);
        // Empty graph — item arm should be skipped entirely
        assertNotEquals(GoalType.ITEM, spec.getType());
    }

    @Test
    public void item_detection_unknown_item_falls_through_to_task_batch() {
        TaskRepository repo = mockRepoWithUnlockables();
        ItemDependencyGraph graph = mock(ItemDependencyGraph.class);
        when(graph.isEmpty()).thenReturn(false);
        when(graph.knownNames()).thenReturn(new HashSet<>(Arrays.asList("rune platebody")));
        when(graph.findItemByName(anyString())).thenReturn(null);

        GoalSpec spec = GoalSpecParser.parse("get dragon scimitar", repo, graph);
        assertEquals(GoalType.TASK_BATCH, spec.getType());
    }

    @Test
    public void item_detection_null_graph_uses_2arg_path() {
        TaskRepository repo = mockRepoWithUnlockables();
        // 3-arg call with null graph should behave like 2-arg overload
        GoalSpec spec = GoalSpecParser.parse("get dragon scimitar", repo, null);
        assertEquals(GoalType.TASK_BATCH, spec.getType());
    }

    @Test
    public void two_arg_overload_delegates_correctly() {
        TaskRepository repo = mockRepoWithUnlockables();
        // 2-arg overload — should work identically to 3-arg with null graph
        GoalSpec spec = GoalSpecParser.parse("plan unlock the Grimoire relic", repo);
        assertEquals(GoalType.RELIC, spec.getType());
    }
}
