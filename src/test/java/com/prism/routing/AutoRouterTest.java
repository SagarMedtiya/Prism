package com.prism.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.config.ConfigLoader;
import com.prism.dto.ChatCompletionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoRouterTest {

    private AutoRouter autoRouter;

    @BeforeEach
    void setUp() throws Exception {
        ConfigLoader loader = new ConfigLoader(new ObjectMapper());
        loader.load();
        AliasResolver aliasResolver = new AliasResolver(loader);
        DifficultyClassifier classifier = new DifficultyClassifier();
        autoRouter = new AutoRouter(classifier, aliasResolver);
    }

    private ChatCompletionRequest requestWith(String content) {
        return ChatCompletionRequest.builder()
                .model("auto")
                .messages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .role("user")
                                .content(content)
                                .build()))
                .build();
    }

    @Test
    void easyPromptRoutesToFast() {
        RouteResult result = autoRouter.route(requestWith("What is the capital of France?"));
        assertEquals("mock-fast-v1", result.getPrimaryModel());
        assertEquals("fast", result.getResolvedAlias());
    }

    @Test
    void hardPromptRoutesToSmart() {
        RouteResult result = autoRouter.route(
                requestWith("Analyze the trade-off between consistency and availability in distributed systems"));
        assertEquals("mock-smart-v1", result.getPrimaryModel());
        assertEquals("smart", result.getResolvedAlias());
    }

    @Test
    void emptyMessagesDefaultsToSmart() {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("auto")
                .messages(List.of())
                .build();
        RouteResult result = autoRouter.route(request);
        assertNotNull(result.getPrimaryModel());
    }

    @Test
    void routeResultHasFallbacks() {
        RouteResult result = autoRouter.route(requestWith("Hello"));
        assertNotNull(result.getFallbacks());
    }
}
