package com.prism.provider.openrouter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.dto.ChatCompletionChunk;
import com.prism.dto.ChatCompletionRequest;
import com.prism.dto.ChatCompletionResponse;
import com.prism.provider.ProviderAdapter;
import com.prism.provider.mock.ProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "prism.openrouter.api-key")
public class OpenRouterProvider implements ProviderAdapter {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private static final List<String> SUPPORTED_MODELS = List.of(
            "openai/gpt-4o-mini",
            "openai/gpt-4o",
            "anthropic/claude-sonnet-4",
            "google/gemini-2.0-flash-001",
            "meta-llama/llama-3.3-70b-instruct"
    );

    public OpenRouterProvider(
            @Value("${prism.openrouter.api-key}") String apiKey,
            @Value("${prism.openrouter.base-url:https://openrouter.ai/api/v1}") String baseUrl,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("OpenRouter provider initialized with base URL: {}", baseUrl);
    }

    @Override
    public String getProviderId() {
        return "openrouter";
    }

    @Override
    public List<String> getSupportedModels() {
        return SUPPORTED_MODELS;
    }

    @Override
    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request, String modelId) {
        Map<String, Object> body = buildRequestBody(request, modelId, false);

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class)
                                .map(errBody -> new ProviderException(
                                        resp.statusCode().value(),
                                        "OpenRouter error: " + errBody)))
                .bodyToMono(ChatCompletionResponse.class)
                .doOnNext(resp -> log.debug("OpenRouter response for {}: {} tokens",
                        modelId, resp.getUsage() != null ? resp.getUsage().getTotalTokens() : "unknown"))
                .onErrorMap(ex -> !(ex instanceof ProviderException),
                        ex -> new ProviderException(502, "OpenRouter call failed: " + ex.getMessage()));
    }

    @Override
    public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request, String modelId) {
        Map<String, Object> body = buildRequestBody(request, modelId, true);

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class)
                                .map(errBody -> new ProviderException(
                                        resp.statusCode().value(),
                                        "OpenRouter stream error: " + errBody)))
                .bodyToFlux(String.class)
                .filter(line -> !line.isBlank() && !line.equals("[DONE]"))
                .map(line -> {
                    String data = line.startsWith("data: ") ? line.substring(6) : line;
                    if (data.equals("[DONE]")) return null;
                    try {
                        return objectMapper.readValue(data, ChatCompletionChunk.class);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse SSE chunk: {}", data);
                        return null;
                    }
                })
                .filter(chunk -> chunk != null)
                .onErrorMap(ex -> !(ex instanceof ProviderException),
                        ex -> new ProviderException(502, "OpenRouter stream failed: " + ex.getMessage()));
    }

    private Map<String, Object> buildRequestBody(ChatCompletionRequest request, String modelId, boolean stream) {
        List<Map<String, String>> messages = request.getMessages().stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .toList();

        var body = new java.util.HashMap<String, Object>();
        body.put("model", modelId);
        body.put("messages", messages);
        body.put("stream", stream);
        body.put("temperature", request.getTemperature());
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        return body;
    }
}
