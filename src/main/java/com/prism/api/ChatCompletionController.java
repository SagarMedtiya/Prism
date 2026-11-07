package com.prism.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.auth.AuthService;
import com.prism.cache.SemanticCacheService;
import com.prism.dto.*;
import com.prism.limits.LimitService;
import com.prism.logging.RequestLogger;
import com.prism.metering.CostCalculator;
import com.prism.model.RequestLog;
import com.prism.model.UsageRecord;
import com.prism.model.VirtualKey;
import com.prism.provider.ProviderCaller;
import com.prism.provider.mock.ProviderException;
import com.prism.routing.AliasResolver;
import com.prism.routing.AutoRouter;
import com.prism.routing.RouteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ChatCompletionController {

    private final AuthService authService;
    private final LimitService limitService;
    private final AliasResolver aliasResolver;
    private final AutoRouter autoRouter;
    private final ProviderCaller providerCaller;
    private final CostCalculator costCalculator;
    private final RequestLogger requestLogger;
    private final SemanticCacheService cacheService;
    private final ObjectMapper objectMapper;

    @PostMapping("/chat/completions")
    public Mono<ResponseEntity<?>> chatCompletions(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ChatCompletionRequest request) {

        long startTime = System.currentTimeMillis();

        String token = extractBearerToken(authHeader);
        if (token == null) {
            return rejectResponse("invalid_key", "Missing or invalid Authorization header", 401,
                    null, request.getModel(), startTime);
        }

        return authService.authenticate(token)
                .switchIfEmpty(Mono.defer(() -> rejectResponse("invalid_key", "Invalid API key", 401,
                        null, request.getModel(), startTime).then(Mono.empty())))
                .flatMap(key -> processRequest(key, request, startTime));
    }

    private Mono<ResponseEntity<?>> processRequest(VirtualKey key,
            ChatCompletionRequest request,
            long startTime) {
        String requestedModel = request.getModel();

        RouteResult route;
        if (aliasResolver.isAutoAlias(requestedModel)) {
            route = autoRouter.route(request);
        } else {
            route = aliasResolver.resolve(requestedModel);
        }

        if (!limitService.isModelAllowed(key, requestedModel)) {
            String resolvedAlias = route.getResolvedAlias();
            if (resolvedAlias == null || !limitService.isModelAllowed(key, resolvedAlias)) {
                return rejectResponse("model_not_allowed",
                        "Model '" + requestedModel + "' is not in your allowlist", 403,
                        key.getId(), requestedModel, startTime);
            }
        }

        return limitService.checkAndIncrementRpm(key)
                .flatMap(admitted -> {
                    if (!admitted) {
                        return rejectResponse("rate_limited",
                                "Rate limit exceeded. Try again later.", 429,
                                key.getId(), requestedModel, startTime);
                    }

                    int estimatedPromptTokens = estimatePromptTokens(request);
                    long estimatedCost = costCalculator.estimateMaxCost(
                            route.getPrimaryModel(), estimatedPromptTokens,
                            request.getMaxTokens() != null ? request.getMaxTokens() : 4096);

                    return limitService.reserveBudget(key, estimatedCost)
                            .flatMap(budgetOk -> {
                                if (!budgetOk) {
                                    return rejectResponse("budget_exhausted",
                                            "Monthly budget exhausted", 402,
                                            key.getId(), requestedModel, startTime);
                                }

                                if (request.isStream()) {
                                    return handleStreaming(key, request, route,
                                            estimatedCost, startTime);
                                } else {
                                    return handleNonStreaming(key, request, route,
                                            estimatedCost, startTime);
                                }
                            });
                });
    }

    private Mono<ResponseEntity<?>> handleNonStreaming(VirtualKey key,
            ChatCompletionRequest request,
            RouteResult route,
            long estimatedCost,
            long startTime) {

        if (cacheService.isCacheable(request)) {
            return cacheService.lookup(key.getId(), request)
                    .flatMap(optHit -> {
                        if (optHit.isPresent()) {
                            SemanticCacheService.CacheHit hit = optHit.get();
                            long latency = System.currentTimeMillis() - startTime;

                            Mono<Void> release = limitService.releaseBudget(key, estimatedCost);

                            RequestLog logEntry = RequestLog.builder()
                                    .keyId(key.getId())
                                    .requestModel(request.getModel())
                                    .resolvedModel(route.getPrimaryModel())
                                    .status("success")
                                    .cacheResult("hit")
                                    .latencyMs(latency)
                                    .build();

                            UsageRecord usage = UsageRecord.builder()
                                    .keyId(key.getId())
                                    .model(request.getModel())
                                    .cacheResult("hit")
                                    .tokensSaved(hit.promptTokens() + hit.completionTokens())
                                    .latencyMs(latency)
                                    .status("success")
                                    .build();

                            return release
                                    .then(requestLogger.logAndRecord(logEntry, usage))
                                    .thenReturn(ResponseEntity.ok()
                                            .header("x-prism-provider", "cache")
                                            .header("x-prism-cache", "hit")
                                            .header("x-prism-fallback", "false")
                                            .header("x-prism-cost-usd", "0")
                                            .body(hit.response()));
                        }

                        return callProviderAndRespond(key, request, route,
                                estimatedCost, startTime);
                    });
        }

        return callProviderAndRespond(key, request, route, estimatedCost, startTime);
    }

    @SuppressWarnings("unchecked")
    private Mono<ResponseEntity<?>> callProviderAndRespond(VirtualKey key,
            ChatCompletionRequest request,
            RouteResult route,
            long estimatedCost,
            long startTime) {
        return providerCaller.complete(request, route.getPrimaryModel(), route.getFallbacks())
                .<ResponseEntity<?>>flatMap(result -> {
                    long latency = System.currentTimeMillis() - startTime;
                    ChatCompletionResponse response = result.response();

                    int promptTokens = response.getUsage().getPromptTokens();
                    int completionTokens = response.getUsage().getCompletionTokens();
                    long actualCost = costCalculator.computeCost(
                            result.modelId(), promptTokens, completionTokens);

                    long excess = estimatedCost - actualCost;
                    Mono<Void> release = limitService.releaseBudget(key, excess);

                    Mono<Void> cacheWrite = cacheService.isCacheable(request) ?
                            cacheService.store(key.getId(), request, response,
                                    promptTokens, completionTokens) :
                            Mono.empty();

                    RequestLog logEntry = RequestLog.builder()
                            .keyId(key.getId())
                            .requestModel(request.getModel())
                            .resolvedModel(route.getPrimaryModel())
                            .servingModel(result.modelId())
                            .provider(result.providerId())
                            .status("success")
                            .promptTokens(promptTokens)
                            .completionTokens(completionTokens)
                            .costMicroUsd(actualCost)
                            .cacheResult("miss")
                            .fallback(result.fallback())
                            .fallbackReason(result.fallbackReason())
                            .latencyMs(latency)
                            .build();

                    UsageRecord usage = UsageRecord.builder()
                            .keyId(key.getId())
                            .model(result.modelId())
                            .provider(result.providerId())
                            .promptTokens(promptTokens)
                            .completionTokens(completionTokens)
                            .costMicroUsd(actualCost)
                            .cacheResult("miss")
                            .fallback(result.fallback())
                            .latencyMs(latency)
                            .status("success")
                            .build();

                    ResponseEntity<?> resp = ResponseEntity.ok()
                                    .header("x-prism-provider", result.providerId())
                                    .header("x-prism-cache", "miss")
                                    .header("x-prism-fallback", String.valueOf(result.fallback()))
                                    .header("x-prism-cost-usd",
                                            String.format("%.6f", actualCost / 1_000_000.0))
                                    .body(response);

                    return release
                            .then(cacheWrite)
                            .then(requestLogger.logAndRecord(logEntry, usage))
                            .thenReturn(resp);
                })
                .onErrorResume(ex -> {
                    long latency = System.currentTimeMillis() - startTime;
                    limitService.releaseBudget(key, estimatedCost).subscribe();

                    String errorCode = ex instanceof ProviderException pe
                            ? (pe.getStatusCode() == 503 ? "provider_unavailable" : "upstream_error")
                            : "internal_error";
                    int status = ex instanceof ProviderException pe ? pe.getStatusCode() : 500;

                    ResponseEntity<?> errResp = ResponseEntity.status(status)
                                    .header("x-prism-provider", "none")
                                    .header("x-prism-cache", "miss")
                                    .header("x-prism-fallback", "false")
                                    .header("x-prism-cost-usd", "0")
                                    .body(ErrorResponse.of(errorCode, ex.getMessage()));

                    return logRejection(key.getId(), request.getModel(), errorCode, latency)
                            .thenReturn(errResp);
                });
    }

    private Mono<ResponseEntity<?>> handleStreaming(VirtualKey key,
            ChatCompletionRequest request,
            RouteResult route,
            long estimatedCost,
            long startTime) {
        ProviderCaller.StreamResult streamResult = providerCaller.stream(request, route.getPrimaryModel(),
                route.getFallbacks());

        AtomicReference<ChatCompletionResponse.Usage> finalUsage = new AtomicReference<>();
        AtomicReference<String> servingModel = new AtomicReference<>(streamResult.modelId());

        Flux<ServerSentEvent<String>> sseFlux = streamResult.chunks()
                .map(chunk -> {
                    if (chunk.getUsage() != null) {
                        finalUsage.set(chunk.getUsage());
                    }
                    if (chunk.getModel() != null) {
                        servingModel.set(chunk.getModel());
                    }
                    try {
                        return ServerSentEvent.<String>builder()
                                .data(objectMapper.writeValueAsString(chunk))
                                .build();
                    } catch (Exception e) {
                        return ServerSentEvent.<String>builder()
                                .data("{}")
                                .build();
                    }
                })
                .concatWith(Mono.fromCallable(() -> {
                    long latency = System.currentTimeMillis() - startTime;
                    ChatCompletionResponse.Usage usage = finalUsage.get();

                    int promptTokens = usage != null ? usage.getPromptTokens() : 0;
                    int completionTokens = usage != null ? usage.getCompletionTokens() : 0;
                    long actualCost = costCalculator.computeCost(
                            servingModel.get(), promptTokens, completionTokens);

                    limitService.releaseBudget(key, estimatedCost - actualCost).subscribe();

                    RequestLog logEntry = RequestLog.builder()
                            .keyId(key.getId())
                            .requestModel(request.getModel())
                            .resolvedModel(route.getPrimaryModel())
                            .servingModel(servingModel.get())
                            .provider(streamResult.providerId())
                            .status("success")
                            .promptTokens(promptTokens)
                            .completionTokens(completionTokens)
                            .costMicroUsd(actualCost)
                            .cacheResult("miss")
                            .fallback(streamResult.fallback())
                            .fallbackReason(streamResult.fallbackReason())
                            .latencyMs(latency)
                            .build();

                    UsageRecord usageRecord = UsageRecord.builder()
                            .keyId(key.getId())
                            .model(servingModel.get())
                            .provider(streamResult.providerId())
                            .promptTokens(promptTokens)
                            .completionTokens(completionTokens)
                            .costMicroUsd(actualCost)
                            .cacheResult("miss")
                            .fallback(streamResult.fallback())
                            .latencyMs(latency)
                            .status("success")
                            .build();

                    requestLogger.logAndRecord(logEntry, usageRecord).subscribe();

                    return ServerSentEvent.<String>builder()
                            .data("[DONE]")
                            .build();
                }))
                .onErrorResume(ex -> {
                    long latency = System.currentTimeMillis() - startTime;
                    limitService.releaseBudget(key, estimatedCost).subscribe();
                    logRejection(key.getId(), request.getModel(), "stream_error", latency).subscribe();

                    String errorJson;
                    try {
                        errorJson = objectMapper.writeValueAsString(
                                ErrorResponse.of("stream_error", ex.getMessage()));
                    } catch (Exception e) {
                        errorJson = "{\"error\":{\"message\":\"Stream error\"}}";
                    }

                    return Flux.just(
                            ServerSentEvent.<String>builder().data(errorJson).build(),
                            ServerSentEvent.<String>builder().data("[DONE]").build());
                });

        return Mono.just(ResponseEntity.ok()
                .header("x-prism-provider", streamResult.providerId())
                .header("x-prism-cache", "miss")
                .header("x-prism-fallback", String.valueOf(streamResult.fallback()))
                .header("x-prism-cost-usd", "0")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sseFlux));
    }

    private String extractBearerToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private int estimatePromptTokens(ChatCompletionRequest request) {
        return request.getMessages().stream()
                .mapToInt(m -> m.getContent() != null ? m.getContent().split("\\s+").length : 0)
                .sum();
    }

    private Mono<ResponseEntity<?>> rejectResponse(String code, String message, int status,
            Long keyId, String model, long startTime) {
        long latency = System.currentTimeMillis() - startTime;

        Mono<Void> logMono = keyId != null ? logRejection(keyId, model, code, latency) : Mono.empty();

        return logMono.thenReturn(ResponseEntity.status(status)
                .header("x-prism-provider", "none")
                .header("x-prism-cache", "miss")
                .header("x-prism-fallback", "false")
                .header("x-prism-cost-usd", "0")
                .body(ErrorResponse.of(code, message)));
    }

    private Mono<Void> logRejection(Long keyId, String model, String errorCode, long latencyMs) {
        RequestLog logEntry = RequestLog.builder()
                .keyId(keyId)
                .requestModel(model)
                .status("rejected")
                .errorCode(errorCode)
                .latencyMs(latencyMs)
                .build();
        return requestLogger.logRequest(logEntry);
    }
}
