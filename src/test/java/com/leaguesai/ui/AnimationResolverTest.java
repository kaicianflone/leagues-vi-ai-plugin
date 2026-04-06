package com.leaguesai.ui;

import org.junit.Test;
import java.util.Collections;
import java.util.HashSet;
import java.util.Arrays;
import static org.junit.Assert.*;

public class AnimationResolverTest {

    @Test
    public void testResolveFishing() {
        AnimationType result = AnimationResolver.resolve("skilling", "fishing",
                Collections.emptySet(), false);
        assertEquals(AnimationType.FISHING, result);
    }

    @Test
    public void testResolveMeleeCombat() {
        // item 4151 is the abyssal whip — not in ranged/magic sets → MELEE
        HashSet<Integer> equipped = new HashSet<>(Arrays.asList(4151));
        AnimationType result = AnimationResolver.resolve("combat", null, equipped, false);
        assertEquals(AnimationType.MELEE, result);
    }

    @Test
    public void testResolveIdleWhenNoTask() {
        AnimationType result = AnimationResolver.resolve(null, null,
                Collections.emptySet(), false);
        assertEquals(AnimationType.IDLE, result);
    }

    @Test
    public void testResolveWalkingWhenInTransit() {
        AnimationType result = AnimationResolver.resolve("skilling", "fishing",
                Collections.emptySet(), true);
        assertEquals(AnimationType.WALKING, result);
    }

    @Test
    public void testResolveRangedCombat() {
        // 839 is a bow
        HashSet<Integer> equipped = new HashSet<>(Arrays.asList(839));
        AnimationType result = AnimationResolver.resolve("combat", null, equipped, false);
        assertEquals(AnimationType.RANGED, result);
    }

    @Test
    public void testResolveMageCombat() {
        // 1381 is a staff
        HashSet<Integer> equipped = new HashSet<>(Arrays.asList(1381));
        AnimationType result = AnimationResolver.resolve("combat", null, equipped, false);
        assertEquals(AnimationType.MAGE, result);
    }

    @Test
    public void testResolveBanking() {
        AnimationType result = AnimationResolver.resolve("banking", null,
                Collections.emptySet(), false);
        assertEquals(AnimationType.BANKING, result);
    }

    @Test
    public void testResolveCooking() {
        AnimationType result = AnimationResolver.resolve("skilling", "cooking",
                Collections.emptySet(), false);
        assertEquals(AnimationType.COOKING, result);
    }

    @Test
    public void testResolveMining() {
        AnimationType result = AnimationResolver.resolve("skilling", "mining",
                Collections.emptySet(), false);
        assertEquals(AnimationType.MINING, result);
    }

    @Test
    public void testResolveWoodcutting() {
        AnimationType result = AnimationResolver.resolve("skilling", "woodcutting",
                Collections.emptySet(), false);
        assertEquals(AnimationType.WOODCUTTING, result);
    }
}
