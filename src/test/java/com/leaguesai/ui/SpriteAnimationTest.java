package com.leaguesai.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpriteAnimationTest {

    @Test
    public void testParseFrames() {
        String content = " o \n/|\\\n/ \\\n---\n \\o/\n |\n/ \\";
        SpriteAnimation anim = SpriteAnimation.parse(content);
        assertEquals(2, anim.getFrameCount());
        String[] frame0 = anim.getFrame(0);
        assertEquals(3, frame0.length);
        assertEquals(" o ", frame0[0]);
        assertEquals("/|\\", frame0[1]);
        assertEquals("/ \\", frame0[2]);
        String[] frame1 = anim.getFrame(1);
        assertEquals(3, frame1.length);
        assertEquals(" \\o/", frame1[0]);
    }

    @Test
    public void testCyclesFrames() {
        String content = "A\n---\nB\n---\nC";
        SpriteAnimation anim = SpriteAnimation.parse(content);
        assertEquals(3, anim.getFrameCount());
        // getFrame(3) should wrap to frame index 0
        assertArrayEquals(anim.getFrame(0), anim.getFrame(3));
    }

    @Test
    public void testEmptyContent() {
        SpriteAnimation anim = SpriteAnimation.parse("");
        assertEquals(1, anim.getFrameCount());
        assertNotNull(anim.getFrame(0));
    }

    @Test
    public void testNullContent() {
        SpriteAnimation anim = SpriteAnimation.parse(null);
        assertEquals(1, anim.getFrameCount());
        assertNotNull(anim.getFrame(0));
    }
}
