package com.prism.provider;

import com.prism.config.PrismProperties;
import com.prism.dto.ChatCompletionRequest;
import com.prism.dto.ChatCompletionResponse;
import com.prism.provider.mock.MockProviderA;
import com.prism.provider.mock.MockProviderB;
import com.prism.provider.mock.ProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCallerTest {

    private MockProviderA providerA;
    private MockProviderB providerB;
    private ProviderCaller caller;

    @BeforeEach
    void setUp() {
        providerA = new MockProviderA();
        providerB = new MockProviderB();

        ProviderRegistry registry = new ProviderRegistry(List.of(providerA, providerB));

        PrismProperties props = new PrismProperties();
        props.getProviders().getTimeout().setTotalMs(5000);
        props.getProviders().getRetry().setMaxAttempts(1);
        props.getProviders().getRetry().setInitialDelayMs(100);
        props.getProviders().getRetry().setMaxDelayMs(200);

        caller = new ProviderCaller(registry, props);
    }

    private ChatCompletionRequest sampleRequest() {
        return ChatCompletionRequest.builder()
                .model("mock-fast-v1")
                .messages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .role("user")
                                .content("Hello")
                                .build()))
                .build();
    }

    @Test
    void completePrimarySuccess() {
        StepVerifier.create(caller.complete(sampleRequest(), "mock-fast-v1", List.of("mock-fast-v2")))
                .assertNext(result -> {
                    assertFalse(result.fallback());
                    assertEquals("mock-fast-v1", result.modelId());
                    assertEquals("mock-provider-a", result.providerId());
                    assertNull(result.fallbackReason());
                    assertNotNull(result.response());
                })
                .verifyComplete();
    }

    @Test
    void completeFailoverWhenPrimaryDown() {
        providerA.setDown(true);

        StepVerifier.create(caller.complete(sampleRequest(), "mock-fast-v1", List.of("mock-fast-v2")))
                .assertNext(result -> {
                    assertTrue(result.fallback());
                    assertEquals("mock-fast-v2", result.modelId());
                    assertEquals("mock-provider-b", result.providerId());
                    assertNotNull(result.fallbackReason());
                    assertTrue(result.fallbackReason().contains("Primary failed"));
                })
                .verifyComplete();
    }

    @Test
    void completeAllProvidersDownReturns503() {
        providerA.setDown(true);
        providerB.setDown(true);

        StepVerifier.create(caller.complete(sampleRequest(), "mock-fast-v1", List.of("mock-fast-v2")))
                .expectErrorMatches(ex -> ex instanceof ProviderException pe
                        && pe.getStatusCode() == 503
                        && pe.getMessage().contains("All providers failed"))
                .verify();
    }

    @Test
    void completeUnknownModelReturns404() {
        assertThrows(ProviderException.class, () ->
                caller.complete(sampleRequest(), "nonexistent", List.of()).block());
    }

    @Test
    void streamPrimarySuccess() {
        ProviderCaller.StreamResult result = caller.stream(sampleRequest(), "mock-fast-v1", List.of());
        assertEquals("mock-provider-a", result.providerId());
        assertFalse(result.fallback());

        StepVerifier.create(result.chunks())
                .thenConsumeWhile(chunk -> true)
                .verifyComplete();
    }

    @Test
    void streamUnknownModelWithFallback() {
        ProviderCaller.StreamResult result = caller.stream(
                sampleRequest(), "nonexistent", List.of("mock-fast-v1"));
        assertEquals("mock-provider-a", result.providerId());
    }

    @Test
    void streamNoProviderNoFallbackReturnsError() {
        ProviderCaller.StreamResult result = caller.stream(
                sampleRequest(), "nonexistent", List.of());
        assertNull(result.providerId());

        StepVerifier.create(result.chunks())
                .expectError(ProviderException.class)
                .verify();
    }
}
