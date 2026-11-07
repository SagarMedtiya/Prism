package com.prism.provider;

import com.prism.dto.ChatCompletionRequest;
import com.prism.dto.ChatCompletionResponse;
import com.prism.dto.ChatCompletionChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderAdapter {

    /** Provider identifier, e.g. "mock-provider-a". */
    String getProviderId();

    /** Which models this adapter can serve. */
    java.util.List<String> getSupportedModels();

    /** Non-streaming completion. */
    Mono<ChatCompletionResponse> complete(ChatCompletionRequest request, String modelId);

    /** Streaming completion. Emits chunks, including a final chunk with usage. */
    Flux<ChatCompletionChunk> stream(ChatCompletionRequest request, String modelId);
}
