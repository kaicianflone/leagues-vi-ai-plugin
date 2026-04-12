package com.leaguesai.agent;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link ChatService#cancelPendingPlan()}.
 *
 * <p>ChatService is constructed with all-null dependencies for these
 * lightweight tests — only the planGeneration counter is exercised.
 */
public class ChatServiceCancelTest {

    @Test
    public void cancelPendingPlan_doesNotThrowWithNullDependencies() {
        ChatService service = new ChatService(null, null, null, null, null);
        service.cancelPendingPlan();  // must not throw
    }

    @Test
    public void cancelPendingPlan_incrementsGenerationCounterByOne() {
        ChatService service = new ChatService(null, null, null, null, null);
        long before = service.getPlanGeneration();
        service.cancelPendingPlan();
        assertEquals("each cancel must bump generation by exactly 1",
                before + 1, service.getPlanGeneration());
    }

    @Test
    public void cancelPendingPlan_eachCallIncrementsByOne() {
        ChatService service = new ChatService(null, null, null, null, null);
        service.cancelPendingPlan();
        service.cancelPendingPlan();
        service.cancelPendingPlan();
        assertEquals("three cancels must produce generation == 3", 3, service.getPlanGeneration());
    }

    @Test
    public void cancelPendingPlan_stalePlanWouldBeDropped() {
        // Simulate the stale-plan guard: a plan captures gen=0, then cancel fires
        // twice. The plan's generation (0) no longer equals the current generation
        // (2), so the callback would be dropped.
        ChatService service = new ChatService(null, null, null, null, null);
        long capturedGen = service.getPlanGeneration();  // simulates myGen at plan start

        service.cancelPendingPlan();  // generation → 1
        service.cancelPendingPlan();  // generation → 2

        // The guard condition: myGen != planGeneration.get()
        assertTrue("stale plan should be detected",
                capturedGen != service.getPlanGeneration());
    }

    @Test
    public void initialGenerationIsZero() {
        ChatService service = new ChatService(null, null, null, null, null);
        assertEquals(0L, service.getPlanGeneration());
    }
}
