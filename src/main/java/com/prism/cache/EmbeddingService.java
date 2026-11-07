package com.prism.cache;

import com.prism.dto.ChatCompletionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Slf4j
@Service
public class EmbeddingService {

    private static final int EMBEDDING_DIM = 128;

    public String normalizeRequest(ChatCompletionRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("model:").append(request.getModel()).append("\n");
        for (ChatCompletionRequest.Message msg : request.getMessages()) {
            sb.append(msg.getRole()).append(":").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    public String computePromptHash(String normalizedText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedText.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public float[] computeEmbedding(String text) {
        float[] embedding = new float[EMBEDDING_DIM];

        String lower = text.toLowerCase();
        for (int i = 0; i < lower.length() - 2; i++) {
            int hash = lower.substring(i, i + 3).hashCode();
            int idx = Math.abs(hash) % EMBEDDING_DIM;
            embedding[idx] += 1.0f;
        }

        float norm = 0;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] /= norm;
            }
        }

        return embedding;
    }

    public double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;

        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public byte[] toBytes(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * 4);
        for (float v : embedding) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    public float[] fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] embedding = new float[bytes.length / 4];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = buffer.getFloat();
        }
        return embedding;
    }
}
