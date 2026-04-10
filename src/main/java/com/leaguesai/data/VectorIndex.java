package com.leaguesai.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class VectorIndex {

    private final Map<String, float[]> vectors;
    private final Map<String, float[]> itemVectors;

    public VectorIndex(Map<String, float[]> vectors, Map<String, float[]> itemVectors) {
        this.vectors = vectors != null ? vectors : Collections.emptyMap();
        this.itemVectors = itemVectors != null ? itemVectors : Collections.emptyMap();
    }

    public VectorIndex(Map<String, float[]> vectors) {
        this(vectors, Collections.emptyMap());
    }

    /** Returns true if no embeddings have been loaded. */
    public boolean isEmpty() {
        return vectors == null || vectors.isEmpty();
    }

    /** Number of indexed vectors. */
    public int size() {
        return vectors == null ? 0 : vectors.size();
    }

    /** Number of indexed item vectors. */
    public int getItemCount() {
        return itemVectors.size();
    }

    /**
     * Returns up to {@code limit} task IDs ordered by cosine similarity to the query vector,
     * highest similarity first.
     */
    public List<String> searchSimilar(float[] query, int limit) {
        if (vectors.isEmpty() || query == null || query.length == 0) {
            return Collections.emptyList();
        }

        List<Map.Entry<String, Float>> scored = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : vectors.entrySet()) {
            float sim = cosineSimilarity(query, entry.getValue());
            scored.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), sim));
        }

        scored.sort((e1, e2) -> Float.compare(e2.getValue(), e1.getValue()));

        List<String> result = new ArrayList<>();
        int count = Math.min(limit, scored.size());
        for (int i = 0; i < count; i++) {
            result.add(scored.get(i).getKey());
        }
        return result;
    }

    /**
     * Returns up to {@code limit} item IDs ordered by cosine similarity to the query vector,
     * highest similarity first. Operates on the item vectors space.
     */
    public List<String> searchItems(float[] query, int limit) {
        if (itemVectors.isEmpty() || query == null || query.length == 0) {
            return Collections.emptyList();
        }

        List<Map.Entry<String, Float>> scored = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : itemVectors.entrySet()) {
            float sim = cosineSimilarity(query, entry.getValue());
            scored.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), sim));
        }

        scored.sort((e1, e2) -> Float.compare(e2.getValue(), e1.getValue()));

        List<String> result = new ArrayList<>();
        int count = Math.min(limit, scored.size());
        for (int i = 0; i < count; i++) {
            result.add(scored.get(i).getKey());
        }
        return result;
    }

    /**
     * Computes cosine similarity between two vectors.
     * Returns 0.0 if either vector has zero magnitude.
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0.0f;
        }
        int len = Math.min(a.length, b.length);
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < len; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        if (denom == 0.0) {
            return 0.0f;
        }
        return (float) (dot / denom);
    }
}
