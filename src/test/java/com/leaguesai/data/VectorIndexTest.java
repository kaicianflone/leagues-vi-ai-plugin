package com.leaguesai.data;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class VectorIndexTest {

    // --- cosineSimilarity ---

    @Test
    public void cosineSimilarity_identicalVectors_returnsOne() {
        float[] v = {1.0f, 0.5f, -0.5f};
        float sim = VectorIndex.cosineSimilarity(v, v);
        assertEquals(1.0f, sim, 1e-6f);
    }

    @Test
    public void cosineSimilarity_orthogonalVectors_returnsZero() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {0.0f, 1.0f, 0.0f};
        float sim = VectorIndex.cosineSimilarity(a, b);
        assertEquals(0.0f, sim, 1e-6f);
    }

    @Test
    public void cosineSimilarity_oppositeVectors_returnsNegativeOne() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {-1.0f, 0.0f, 0.0f};
        float sim = VectorIndex.cosineSimilarity(a, b);
        assertEquals(-1.0f, sim, 1e-6f);
    }

    @Test
    public void cosineSimilarity_zeroVector_returnsZero() {
        float[] a = {0.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 2.0f, 3.0f};
        float sim = VectorIndex.cosineSimilarity(a, b);
        assertEquals(0.0f, sim, 1e-6f);
    }

    @Test
    public void cosineSimilarity_knownValues() {
        // [1,1,0] and [1,0,0]: dot=1, norm([1,1,0])=sqrt(2), norm([1,0,0])=1
        // sim = 1/sqrt(2) ≈ 0.7071
        float[] a = {1.0f, 1.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        float sim = VectorIndex.cosineSimilarity(a, b);
        assertEquals((float) (1.0 / Math.sqrt(2)), sim, 1e-5f);
    }

    // --- searchSimilar ---

    @Test
    public void searchSimilar_returnsClosestFirst() {
        // query = [1,0,0]
        // "task-x" = [1,0,0]  -> sim 1.0 (identical)
        // "task-y" = [0,1,0]  -> sim 0.0 (orthogonal)
        // "task-z" = [0.7f, 0.7f, 0.0f] -> sim ~0.707
        Map<String, float[]> vecs = new HashMap<>();
        vecs.put("task-x", new float[]{1.0f, 0.0f, 0.0f});
        vecs.put("task-y", new float[]{0.0f, 1.0f, 0.0f});
        vecs.put("task-z", new float[]{0.7f, 0.7f, 0.0f});

        VectorIndex index = new VectorIndex(vecs);
        List<String> results = index.searchSimilar(new float[]{1.0f, 0.0f, 0.0f}, 3);

        assertEquals(3, results.size());
        assertEquals("task-x", results.get(0)); // sim = 1.0
        assertEquals("task-z", results.get(1)); // sim ≈ 0.707
        assertEquals("task-y", results.get(2)); // sim = 0.0
    }

    @Test
    public void searchSimilar_respectsLimit() {
        Map<String, float[]> vecs = new HashMap<>();
        vecs.put("task-a", new float[]{1.0f, 0.0f, 0.0f});
        vecs.put("task-b", new float[]{0.9f, 0.1f, 0.0f});
        vecs.put("task-c", new float[]{0.0f, 1.0f, 0.0f});

        VectorIndex index = new VectorIndex(vecs);
        List<String> results = index.searchSimilar(new float[]{1.0f, 0.0f, 0.0f}, 2);

        assertEquals(2, results.size());
    }

    @Test
    public void searchSimilar_limitLargerThanIndex_returnsAll() {
        Map<String, float[]> vecs = new HashMap<>();
        vecs.put("task-a", new float[]{1.0f, 0.0f, 0.0f});
        vecs.put("task-b", new float[]{0.0f, 1.0f, 0.0f});

        VectorIndex index = new VectorIndex(vecs);
        List<String> results = index.searchSimilar(new float[]{1.0f, 0.0f, 0.0f}, 100);

        assertEquals(2, results.size());
    }

    @Test
    public void searchSimilar_emptyIndex_returnsEmpty() {
        VectorIndex index = new VectorIndex(Collections.emptyMap());
        List<String> results = index.searchSimilar(new float[]{1.0f, 0.0f, 0.0f}, 5);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    public void searchSimilar_limitZero_returnsEmpty() {
        Map<String, float[]> vecs = new HashMap<>();
        vecs.put("task-a", new float[]{1.0f, 0.0f, 0.0f});

        VectorIndex index = new VectorIndex(vecs);
        List<String> results = index.searchSimilar(new float[]{1.0f, 0.0f, 0.0f}, 0);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }
}
