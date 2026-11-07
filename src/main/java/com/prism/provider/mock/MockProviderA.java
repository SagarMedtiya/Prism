package com.prism.provider.mock;

import com.prism.dto.*;
import com.prism.provider.ProviderAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class MockProviderA implements ProviderAdapter {

    private final AtomicBoolean down = new AtomicBoolean(false);
    private final AtomicInteger errorRate = new AtomicInteger(0); // 0-100
    private final AtomicInteger latencyMs = new AtomicInteger(50);

    @Override
    public String getProviderId() {
        return "mock-provider-a";
    }

    @Override
    public List<String> getSupportedModels() {
        return List.of("mock-fast-v1", "mock-smart-v2");
    }

    @Override
    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request, String modelId) {
        if (down.get()) {
            return Mono.error(new ProviderException(503, "Provider is down"));
        }
        if (shouldError()) {
            return Mono.error(new ProviderException(503, "Transient error"));
        }

        String content = generateResponse(request, modelId);
        int promptTokens = estimateTokens(request);
        int completionTokens = content.split("\\s+").length;

        return Mono.just(ChatCompletionResponse.builder()
                .model(modelId)
                .choices(List.of(ChatCompletionResponse.Choice.builder()
                        .index(0)
                        .message(ChatCompletionResponse.Message.builder()
                                .role("assistant")
                                .content(content)
                                .build())
                        .build()))
                .usage(ChatCompletionResponse.Usage.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(promptTokens + completionTokens)
                        .build())
                .build())
                .delayElement(Duration.ofMillis(latencyMs.get()));
    }

    @Override
    public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request, String modelId) {
        if (down.get()) {
            return Flux.error(new ProviderException(503, "Provider is down"));
        }

        String content = generateResponse(request, modelId);
        String[] words = content.split("\\s+");
        String chunkId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        int promptTokens = estimateTokens(request);

        Flux<ChatCompletionChunk> chunks = Flux.range(0, words.length)
                .delayElements(Duration.ofMillis(20))
                .map(i -> ChatCompletionChunk.builder()
                        .id(chunkId)
                        .model(modelId)
                        .choices(List.of(ChatCompletionChunk.ChunkChoice.builder()
                                .index(0)
                                .delta(ChatCompletionChunk.Delta.builder()
                                        .content(words[i] + " ")
                                        .build())
                                .build()))
                        .build());

        // Final chunk with finish_reason and usage
        ChatCompletionChunk finalChunk = ChatCompletionChunk.builder()
                .id(chunkId)
                .model(modelId)
                .choices(List.of(ChatCompletionChunk.ChunkChoice.builder()
                        .index(0)
                        .delta(ChatCompletionChunk.Delta.builder().build())
                        .finishReason("stop")
                        .build()))
                .usage(ChatCompletionResponse.Usage.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(words.length)
                        .totalTokens(promptTokens + words.length)
                        .build())
                .build();

        return chunks.concatWith(Mono.just(finalChunk));
    }

    private String generateResponse(ChatCompletionRequest request, String modelId) {
        String lastMessage = request.getMessages().isEmpty() ? ""
                : request.getMessages().get(request.getMessages().size() - 1).getContent();
        return "Mock response from " + modelId + " (provider-a): " +
                "Regarding your query about '" +
                lastMessage.substring(0, Math.min(50, lastMessage.length())) +
                "', here is a helpful answer with relevant information.";
    }

    private int estimateTokens(ChatCompletionRequest request) {
        return request.getMessages().stream()
                .mapToInt(m -> m.getContent().split("\\s+").length)
                .sum();
    }

    private boolean shouldError() {
        return errorRate.get() > 0 && Math.random() * 100 < errorRate.get();
    }

    // --- Control methods for testing ---

    public void setDown(boolean isDown) {
        this.down.set(isDown);
    }

    public void setErrorRate(int percent) {
        this.errorRate.set(percent);
    }

    public void setLatencyMs(int ms) {
        this.latencyMs.set(ms);
    }
}