package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ItemSourceResolverTest {

    @Test
    public void collectUniqueItems_dedupesAcrossSteps() {
        Map<String, Integer> itemsA = new LinkedHashMap<>();
        itemsA.put("Steel longsword", 1);
        itemsA.put("Coal", 2);
        Task taskA = Task.builder().id("a").name("A").itemsRequired(itemsA).build();

        Map<String, Integer> itemsB = new LinkedHashMap<>();
        itemsB.put("Coal", 5); // duplicate name
        itemsB.put("Iron ore", 1);
        Task taskB = Task.builder().id("b").name("B").itemsRequired(itemsB).build();

        PlannedStep stepA = PlannedStep.builder().task(taskA).build();
        PlannedStep stepB = PlannedStep.builder().task(taskB).build();

        Set<String> unique = ItemSourceResolver.collectUniqueItems(Arrays.asList(stepA, stepB));
        assertEquals(3, unique.size());
        assertTrue(unique.contains("Steel longsword"));
        assertTrue(unique.contains("Coal"));
        assertTrue(unique.contains("Iron ore"));
    }

    @Test
    public void parseReply_extractsNameAndSourceLines() {
        String reply = "Steel longsword :: Smith from 1 steel bar at any anvil\n"
                + "Coal :: Mine at Lumbridge swamp\n"
                + "garbage line without separator\n"
                + "- Iron ore :: Mine at Varrock west iron rocks\n";

        Map<String, String> parsed = ItemSourceResolver.parseReply(reply);
        assertEquals(3, parsed.size());
        assertEquals("Smith from 1 steel bar at any anvil", parsed.get("Steel longsword"));
        assertEquals("Mine at Lumbridge swamp", parsed.get("Coal"));
        assertEquals("Mine at Varrock west iron rocks", parsed.get("Iron ore"));
    }

    @Test
    public void resolveBatch_firesOneCallAndAttachesNotes() throws Exception {
        LlmClient mockClient = mock(LlmClient.class);
        when(mockClient.chatCompletion(anyString(), any()))
                .thenReturn("Steel longsword :: Smith from a steel bar\nCoal :: Mine it");

        Map<String, Integer> itemsA = new LinkedHashMap<>();
        itemsA.put("Steel longsword", 1);
        Task taskA = Task.builder().id("a").name("A").itemsRequired(itemsA).build();

        Map<String, Integer> itemsB = new LinkedHashMap<>();
        itemsB.put("Coal", 2);
        Task taskB = Task.builder().id("b").name("B").itemsRequired(itemsB).build();

        ItemSourceResolver resolver = new ItemSourceResolver(mockClient);
        List<PlannedStep> enriched = resolver.resolveBatch(Arrays.asList(
                PlannedStep.builder().task(taskA).build(),
                PlannedStep.builder().task(taskB).build()
        ));

        // Exactly ONE LLM call regardless of step count
        verify(mockClient, times(1)).chatCompletion(anyString(), any());

        assertNotNull(enriched);
        assertEquals(2, enriched.size());
        assertEquals("Smith from a steel bar",
                enriched.get(0).getItemSourceNotes().get("Steel longsword"));
        assertEquals("Mine it",
                enriched.get(1).getItemSourceNotes().get("Coal"));
    }

    @Test
    public void resolveBatch_promptIncludesAllUniqueItems() throws Exception {
        LlmClient mockClient = mock(LlmClient.class);
        when(mockClient.chatCompletion(anyString(), any())).thenReturn("");

        Map<String, Integer> items = new LinkedHashMap<>();
        items.put("Logs", 1);
        items.put("Tinderbox", 1);
        Task t = Task.builder().id("a").name("A").itemsRequired(items).build();

        new ItemSourceResolver(mockClient)
                .resolveBatch(java.util.Collections.singletonList(
                        PlannedStep.builder().task(t).build()));

        ArgumentCaptor<List<OpenAiClient.Message>> msgs = ArgumentCaptor.forClass(List.class);
        verify(mockClient).chatCompletion(anyString(), msgs.capture());
        String userPrompt = msgs.getValue().get(0).getContent();
        assertTrue("prompt should list Logs", userPrompt.contains("Logs"));
        assertTrue("prompt should list Tinderbox", userPrompt.contains("Tinderbox"));
    }

    @Test
    public void resolveBatch_failureReturnsStepsWithEmptyNotes() throws Exception {
        LlmClient mockClient = mock(LlmClient.class);
        when(mockClient.chatCompletion(anyString(), any()))
                .thenThrow(new IOException("network down"));

        Map<String, Integer> items = new LinkedHashMap<>();
        items.put("Coal", 1);
        Task t = Task.builder().id("a").name("A").itemsRequired(items).build();

        List<PlannedStep> result = new ItemSourceResolver(mockClient)
                .resolveBatch(java.util.Collections.singletonList(
                        PlannedStep.builder().task(t).build()));

        // Plan still loads — graceful degradation
        assertNotNull(result);
        assertEquals(1, result.size());
        // notes map exists (empty) but the step itself is the original (since attach was skipped)
        // The contract: degraded path returns input list unchanged.
    }

    @Test
    public void resolveBatch_emptyPlanReturnsEmpty() {
        ItemSourceResolver resolver = new ItemSourceResolver(mock(LlmClient.class));
        List<PlannedStep> out = resolver.resolveBatch(java.util.Collections.emptyList());
        assertNotNull(out);
        assertEquals(0, out.size());
    }

    // -------------------------------------------------------------------------
    // Two-phase DB-first path (ItemDependencyGraph present)
    // -------------------------------------------------------------------------

    @Test
    public void resolveBatch_dbItemSkipsLlmCall() throws Exception {
        // When ItemDependencyGraph covers all items, the LLM is never called.
        LlmClient mockClient = mock(LlmClient.class);
        ItemDependencyGraph graph = mock(ItemDependencyGraph.class);
        when(graph.isEmpty()).thenReturn(false);

        com.leaguesai.data.model.ItemDependency dep =
                com.leaguesai.data.model.ItemDependency.builder()
                        .itemId("coal")
                        .itemName("Coal")
                        .obtainMethod(ObtainMethod.DROPPED)
                        .sourceName("Scorpion")
                        .build();
        when(graph.findItemByName("Coal")).thenReturn(dep);

        Map<String, Integer> items = new java.util.LinkedHashMap<>();
        items.put("Coal", 1);
        Task t = Task.builder().id("a").name("A").itemsRequired(items).build();

        ItemSourceResolver resolver = new ItemSourceResolver(mockClient, graph);
        List<PlannedStep> result = resolver.resolveBatch(
                java.util.Collections.singletonList(PlannedStep.builder().task(t).build()));

        // LLM must not have been called because the DB covered every item.
        verify(mockClient, org.mockito.Mockito.never()).chatCompletion(anyString(), any());

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue("DB source should be attached",
                result.get(0).getItemSourceNotes().containsKey("Coal"));
    }

    @Test
    public void resolveBatch_partialDbCoverage_onlyUnknownItemsSentToLlm() throws Exception {
        // Items covered by DB stay local; only the remainder goes to the LLM.
        LlmClient mockClient = mock(LlmClient.class);
        when(mockClient.chatCompletion(anyString(), any())).thenReturn("Iron ore :: Mine it");

        ItemDependencyGraph graph = mock(ItemDependencyGraph.class);
        when(graph.isEmpty()).thenReturn(false);

        com.leaguesai.data.model.ItemDependency coalDep =
                com.leaguesai.data.model.ItemDependency.builder()
                        .itemId("coal")
                        .itemName("Coal")
                        .obtainMethod(ObtainMethod.DROPPED)
                        .sourceName("Scorpion")
                        .build();
        when(graph.findItemByName("Coal")).thenReturn(coalDep);
        when(graph.findItemByName("Iron ore")).thenReturn(null);  // not in DB

        Map<String, Integer> items = new java.util.LinkedHashMap<>();
        items.put("Coal", 1);
        items.put("Iron ore", 1);
        Task t = Task.builder().id("a").name("A").itemsRequired(items).build();

        ItemSourceResolver resolver = new ItemSourceResolver(mockClient, graph);
        List<PlannedStep> result = resolver.resolveBatch(
                java.util.Collections.singletonList(PlannedStep.builder().task(t).build()));

        // LLM called exactly once for the un-covered item.
        verify(mockClient, times(1)).chatCompletion(anyString(), any());

        assertNotNull(result);
        assertEquals(1, result.size());
        Map<String, String> notes = result.get(0).getItemSourceNotes();
        assertTrue("DB-covered item should be in notes", notes.containsKey("Coal"));
        assertTrue("LLM-covered item should be in notes", notes.containsKey("Iron ore"));
    }

    @Test
    public void buildSourceLine_droppedItem() {
        com.leaguesai.data.model.ItemDependency dep =
                com.leaguesai.data.model.ItemDependency.builder()
                        .itemId("coal")
                        .itemName("Coal")
                        .obtainMethod(ObtainMethod.DROPPED)
                        .sourceName("Giant bat")
                        .build();
        String line = ItemSourceResolver.buildSourceLine(dep);
        assertNotNull(line);
        assertTrue("Should mention obtain method", line.contains("Dropped"));
        assertTrue("Should mention source name", line.contains("Giant bat"));
    }

    @Test
    public void buildSourceLine_craftedItemWithSkill() {
        com.leaguesai.data.model.ItemDependency dep =
                com.leaguesai.data.model.ItemDependency.builder()
                        .itemId("steel_bar")
                        .itemName("Steel bar")
                        .obtainMethod(ObtainMethod.SMITHED)
                        .skillRequired("smithing")
                        .skillLevel(30)
                        .sourceName("anvil")
                        .build();
        String line = ItemSourceResolver.buildSourceLine(dep);
        assertNotNull(line);
        assertTrue(line.contains("Smithed"));
        assertTrue(line.contains("smithing"));
        assertTrue(line.contains("30"));
        assertTrue(line.contains("anvil"));
    }

    @Test
    public void buildSourceLine_nullReturnsNull() {
        assertNull(ItemSourceResolver.buildSourceLine(null));
    }
}
