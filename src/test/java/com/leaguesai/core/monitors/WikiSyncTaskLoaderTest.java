package com.leaguesai.core.monitors;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * Tests for WikiSyncTaskLoader.extractWikiId which drives all task-completion
 * matching between WikiSync numeric IDs and our "tbz-N" task IDs.
 */
public class WikiSyncTaskLoaderTest {

    /** Call the package-private static helper via reflection. */
    private int extractWikiId(String taskId) throws Exception {
        Method m = WikiSyncTaskLoader.class.getDeclaredMethod("extractWikiId", String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, taskId);
    }

    @Test
    public void extractWikiId_typicalId_returnsNumber() throws Exception {
        assertEquals(3, extractWikiId("tbz-3"));
    }

    @Test
    public void extractWikiId_largeId_returnsNumber() throws Exception {
        assertEquals(1422, extractWikiId("tbz-1422"));
    }

    @Test
    public void extractWikiId_zero_returnsZero() throws Exception {
        assertEquals(0, extractWikiId("tbz-0"));
    }

    @Test
    public void extractWikiId_noDash_returnsNegativeOne() throws Exception {
        assertEquals(-1, extractWikiId("noDash"));
    }

    @Test
    public void extractWikiId_emptyString_returnsNegativeOne() throws Exception {
        assertEquals(-1, extractWikiId(""));
    }

    @Test
    public void extractWikiId_nonNumericSuffix_returnsNegativeOne() throws Exception {
        assertEquals(-1, extractWikiId("tbz-abc"));
    }

    @Test
    public void extractWikiId_multipleDashes_parsesLastSegment() throws Exception {
        // e.g. if id format ever changes to "tbz-v2-5"
        assertEquals(5, extractWikiId("tbz-v2-5"));
    }
}
