package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

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

public class PersonaReviewerTest {

    private static List<PlannedStep> samplePlan() {
        Task t = Task.builder().id("t1").name("Catch Shrimp").area("misthalin").build();
        return Collections.singletonList(
                PlannedStep.builder().task(t).instruction("Catch shrimp at Lumbridge").build()
        );
    }

    @Test
    public void review_callsLlmExactlyOnce_andReturnsTrimmed() throws Exception {
        LlmClient client = mock(LlmClient.class);
        when(client.chatCompletion(anyString(), any())).thenReturn(
                "  B0aty: dangerous\nFaux: slow\nUIM: too many trips\nVerdict: revise  \n");

        String result = new PersonaReviewer(client).review("complete misthalin easy", samplePlan());

        assertNotNull(result);
        assertTrue(result.startsWith("B0aty:"));
        assertTrue(result.contains("Verdict: revise"));
        verify(client, times(1)).chatCompletion(anyString(), any());
    }

    @Test
    public void review_promptIncludesAllThreePersonas() throws Exception {
        LlmClient client = mock(LlmClient.class);
        when(client.chatCompletion(anyString(), any())).thenReturn("ok");

        new PersonaReviewer(client).review("goal", samplePlan());

        ArgumentCaptor<List<OpenAiClient.Message>> msgs = ArgumentCaptor.forClass(List.class);
        verify(client).chatCompletion(anyString(), msgs.capture());
        String prompt = msgs.getValue().get(0).getContent();
        assertTrue("prompt should mention B0aty", prompt.contains("B0aty"));
        assertTrue("prompt should mention Faux", prompt.contains("Faux"));
        assertTrue("prompt should mention UIM", prompt.contains("UIM"));
        assertTrue("prompt should include the goal", prompt.contains("goal"));
    }

    @Test
    public void review_returnsNullOnFailure() throws Exception {
        LlmClient client = mock(LlmClient.class);
        when(client.chatCompletion(anyString(), any()))
                .thenThrow(new IOException("network down"));

        String result = new PersonaReviewer(client).review("g", samplePlan());
        assertNull("failure should yield null so the caller hides the banner", result);
    }

    @Test
    public void review_emptyPlanReturnsNullWithoutCallingLlm() throws Exception {
        LlmClient client = mock(LlmClient.class);
        String result = new PersonaReviewer(client).review("g", Collections.emptyList());
        assertNull(result);
        verify(client, times(0)).chatCompletion(anyString(), any());
    }
}
