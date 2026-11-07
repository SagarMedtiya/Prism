package com.prism.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingServiceTest {

    private final EmbeddingService service = new EmbeddingService();

    @Test
    void identicalTextHasSimilarityOne() {
        float[] a = service.computeEmbedding("What is the capital of France?");
        float[] b = service.computeEmbedding("What is the capital of France?");
        double sim = service.cosineSimilarity(a, b);
        assertEquals(1.0, sim, 0.001);
    }

    @Test
    void similarTextHasHighSimilarity() {
        float[] a = service.computeEmbedding("What is the capital of France?");
        float[] b = service.computeEmbedding("Tell me the capital city of France");
        double sim = service.cosineSimilarity(a, b);
        assertTrue(sim > 0.7, "Expected high similarity, got " + sim);
    }

    @Test
    void differentTextHasLowSimilarity() {
        float[] a = service.computeEmbedding("What is the capital of France?");
        float[] b = service.computeEmbedding("How do neural networks learn?");
        double sim = service.cosineSimilarity(a, b);
        assertTrue(sim < 0.7, "Expected low similarity, got " + sim);
    }

    @Test
    void serializationRoundtrips() {
        float[] original = service.computeEmbedding("test text");
        byte[] bytes = service.toBytes(original);
        float[] restored = service.fromBytes(bytes);
        assertArrayEquals(original, restored);
    }

    @Test
    void promptHashIsDeterministic() {
        String text = "model:fast\nuser:hello\n";
        String h1 = service.computePromptHash(text);
        String h2 = service.computePromptHash(text);
        assertEquals(h1, h2);
    }
}
