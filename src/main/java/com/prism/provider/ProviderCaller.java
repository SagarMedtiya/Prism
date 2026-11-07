package com.prism.provider;

import com.prism.config.PrismProperties;
import com.prism.dto.ChatCompletionChunk;
import com.prism.dto.ChatCompletionRequest;
import com.prism.dto.ChatCompletionResponse;
import com.prism.provider.mock.ProviderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/**
 * Calls providers with timeout, retry, and failover.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderCaller {

    private final ProviderRegistry registry;
    private final PrismProperties props;

    public record CompletionResult(
            ChatCompletionResponse response,
            String providerId,
            String modelId,
            boolean fallback,
            String fallbackReason) {
    }

    public record StreamResult(
            Flux<ChatCompletionChunk> chunks,
            String providerId,
            String modelId,
            boolean fallback,
            String fallbackReason) {
    }

    /**
     * Non-streaming call with retry and failover across the model chain.
     */
    public Mono<CompletionResult> complete(ChatCompletionRequest request,
            String primaryModel,
            List<String> fallbacks) {
        return tryComplete(request, primaryModel)
                .map(resp -> new CompletionResult(resp,
                        providerIdFor(primaryModel), primaryModel, false, null))
                .onErrorResume(ex -> attemptFallbacks(request, fallbacks, ex, 0));
    }

    private Mono<CompletionResult> attemptFallbacks(ChatCompletionRequest request,
            List<String> fallbacks,
            Throwable originalError,
            int index) {
        if (index >= fallbacks.size()) {
            return Mono.error(new ProviderException(503,
                    "All providers failed. Last error: " + originalError.getMessage()));
        }

        String model = fallbacks.get(index);
        String reason = "Primary failed: " + originalError.getMessage();

        return tryComplete(request, model)
                .map(resp -> new CompletionResult(resp,
                        providerIdFor(model), model, true, reason))
                .onErrorResume(ex -> attemptFallbacks(request, fallbacks, ex, index + 1));
    }

    private Mono<ChatCompletionResponse> tryComplete(ChatCompletionRequest request, String modelId) {
        ProviderAdapter adapter = registry.getByModel(modelId)
                .orElseThrow(() -> new ProviderException(404, "No provider for model: " + modelId));

        PrismProperties.Providers.Retry retryConfig = props.getProviders().getRetry();

        return adapter.complete(request, modelId)
                .timeout(Duration.ofMillis(props.getProviders().getTimeout().getTotalMs()))
                .retryWhen(Retry.backoff(retryConfig.getMaxAttempts(),
                        Duration.ofMillis(retryConfig.getInitialDelayMs()))
                        .maxBackoff(Duration.ofMillis(retryConfig.getMaxDelayMs()))
                        .filter(ex -> ex instanceof ProviderException pe && pe.isTransient())
                        .doBeforeRetry(signal -> log.warn("Retrying {} attempt {}: {}",
                                modelId, signal.totalRetries() + 1, signal.failure().getMessage())));
    }

    /**
     * Streaming call — failover only before the first byte.
     */
    public StreamResult stream(ChatCompletionRequest request,
            String primaryModel,
            List<String> fallbacks) {
        ProviderAdapter adapter = registry.getByModel(primaryModel).orElse(null);

        if (adapter == null && !fallbacks.isEmpty()) {
            // Primary not found, try first fallback
            return stream(request, fallbacks.get(0), fallbacks.subList(1, fallbacks.size()));
        }

        if (adapter == null) {
            return new StreamResult(
                    Flux.error(new ProviderException(503, "No provider for model: " + primaryModel)),
                    null, primaryModel, false, null);
        }

        Flux<ChatCompletionChunk> chunks = adapter.stream(request, primaryModel)
                .timeout(Duration.ofMillis(props.getProviders().getTimeout().getTotalMs()));

        // For streaming, we attempt failover only on an immediate error (before first
        // byte).
        // Once chunks start flowing, no failover.
        Flux<ChatCompletionChunk> withFallback = chunks.switchOnFirst((signal, flux) -> {
            if (signal.isOnError() && !fallbacks.isEmpty()) {
                String fallbackModel = fallbacks.get(0);
                log.warn("Streaming failover from {} to {}: {}",
                        primaryModel, fallbackModel, signal.getThrowable().getMessage());
                ProviderAdapter fb = registry.getByModel(fallbackModel).orElse(null);
                if (fb != null) {
                    return fb.stream(request, fallbackModel)
                            .timeout(Duration.ofMillis(props.getProviders().getTimeout().getTotalMs()));
                }
            }
            return flux;
        });

        return new StreamResult(withFallback, adapter.getProviderId(), primaryModel, false, null);
    }

    private String providerIdFor(String modelId) {
        return registry.getByModel(modelId)
                .map(ProviderAdapter::getProviderId)
                .orElse("unknown");
    }
}