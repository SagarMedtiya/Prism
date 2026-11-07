package com.prism.routing;

import com.prism.config.GatewayConfig;
import com.prism.dto.ChatCompletionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class AutoRouter {

    private final DifficultyClassifier classifier;
    private final AliasResolver aliasResolver;

    private static final long CLASSIFIER_TIMEOUT_MS = 500;

    public RouteResult route(ChatCompletionRequest request) {
        GatewayConfig.AliasConfig autoConfig = aliasResolver.getAutoConfig();
        if (autoConfig == null) {
            return aliasResolver.resolve("smart");
        }

        String difficulty;
        try {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> future = executor.submit(
                    () -> classifier.classify(request.getMessages()));
            difficulty = future.get(CLASSIFIER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            executor.shutdown();
        } catch (TimeoutException e) {
            log.warn("Classifier timeout - defaulting to smart");
            difficulty = "hard";
        } catch (Exception e) {
            log.warn("Classifier error - defaulting to smart: {}", e.getMessage());
            difficulty = "hard";
        }

        String targetAlias = autoConfig.getRoutes().getOrDefault(difficulty, "smart");
        log.info("Auto-router: difficulty='{}' -> alias='{}'", difficulty, targetAlias);

        return aliasResolver.resolve(targetAlias);
    }
}
