package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;
import org.junit.Test;

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
}
