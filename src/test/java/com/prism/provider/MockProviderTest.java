package com.prism.provider;

import com.prism.dto.ChatCompletionChunk;
import com.prism.dto.ChatCompletionRequest;
import com.prism.dto.ChatCompletionResponse;
import com.prism.provider.mock.MockProviderA;
import com.prism.provider.mock.MockProviderB;
import com.prism.provider.mock.ProviderException;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockProviderTest {

    private final MockProviderA providerA = new MockProviderA();
    private final MockProviderB providerB = new MockProviderB();

    private ChatCompletionRequest sampleRequest() {
        return ChatCompletionRequest.builder()
                .model("mock-fast-v1")
                .messages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .role("user")
                                .content("Hello world")
                                .build()))
                .build();
    }

    @Test
    void providerAReturnsValidResponse() {
        StepVerifier.create(providerA.complete(sampleRequest(), "mock-fast-v1"))
                .assertNext(resp -> {
                    assertEquals("mock-fast-v1", resp.getModel());
                    assertNotNull(resp.getChoices());
                    assertFalse(resp.getChoices().isEmpty());
                    assertNotNull(resp.getChoices().get(0).getMessage().getContent());
                    assertTrue(resp.getChoices().get(0).getMessage().getContent().contains("provider-a"));
                    assertEquals("assistant", resp.getChoices().get(0).getMessage().getRole());
                    assertNotNull(resp.getUsage());
                    assertTrue(resp.getUsage().getPromptTokens() > 0);
                    assertTrue(resp.getUsage().getCompletionTokens() > 0);
                    assertEquals(resp.getUsage().getPromptTokens() + resp.getUsage().getCompletionTokens(),
                            resp.getUsage().getTotalTokens());
                })
                .verifyComplete();
    }

    @Test
    void providerBReturnsValidResponse() {
        StepVerifier.create(providerB.complete(sampleRequest(), "mock-smart-v1"))
                .assertNext(resp -> {
                    assertEquals("mock-smart-v1", resp.getModel());
                    assertTrue(resp.getChoices().get(0).getMessage().getContent().contains("provider-b"));
                })
                .verifyComplete();
    }

    @Test
    void providerADownReturns503() {
        providerA.setDown(true);
        try {
            StepVerifier.create(providerA.complete(sampleRequest(), "mock-fast-v1"))
                    .expectErrorMatches(ex -> ex instanceof ProviderException pe
                            && pe.getStatusCode() == 503
                            && pe.getMessage().contains("down"))
                    .verify();
        } finally {
            providerA.setDown(false);
        }
    }

    @Test
    void providerASupportedModels() {
        assertEquals(List.of("mock-fast-v1", "mock-smart-v2"), providerA.getSupportedModels());
        assertEquals("mock-provider-a", providerA.getProviderId());
    }

    @Test
    void providerBSupportedModels() {
        assertEquals(List.of("mock-smart-v1", "mock-fast-v2"), providerB.getSupportedModels());
        assertEquals("mock-provider-b", providerB.getProviderId());
    }

    @Test
    void streamingReturnsChunksAndFinalUsage() {
        StepVerifier.create(providerA.stream(sampleRequest(), "mock-fast-v1"))
                .thenConsumeWhile(chunk -> {
                    assertNotNull(chunk.getId());
                    assertEquals("mock-fast-v1", chunk.getModel());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void streamingFinalChunkHasUsage() {
        List<ChatCompletionChunk> chunks = providerA.stream(sampleRequest(), "mock-fast-v1")
                .collectList().block();

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());

        ChatCompletionChunk last = chunks.get(chunks.size() - 1);
        assertNotNull(last.getUsage());
        assertTrue(last.getUsage().getPromptTokens() > 0);
        assertEquals("stop", last.getChoices().get(0).getFinishReason());
    }

    @Test
    void streamingWhenDownReturnsError() {
        providerA.setDown(true);
        try {
            StepVerifier.create(providerA.stream(sampleRequest(), "mock-fast-v1"))
                    .expectError(ProviderException.class)
                    .verify();
        } finally {
            providerA.setDown(false);
        }
    }

    @Test
    void providerExceptionTransience() {
        assertTrue(new ProviderException(503, "").isTransient());
        assertTrue(new ProviderException(500, "").isTransient());
        assertTrue(new ProviderException(429, "").isTransient());
        assertFalse(new ProviderException(404, "").isTransient());
        assertFalse(new ProviderException(400, "").isTransient());
    }
}
