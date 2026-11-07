package com.prism.routing;

import com.prism.config.ConfigLoader;
import com.prism.config.GatewayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AliasResolver {

    private final ConfigLoader configLoader;

    /**
     * Resolves a model name or alias to a primary model and fallback chain.
     * For "auto", the caller must run the classifier first and pass the resolved
     * alias.
     */
    public RouteResult resolve(String modelOrAlias) {
        GatewayConfig config = configLoader.getGatewayConfig();
        GatewayConfig.AliasConfig aliasConfig = config.getAliases().get(modelOrAlias);

        if (aliasConfig != null && aliasConfig.getPrimary() != null) {
            // It's an alias like "fast" or "smart"
            return new RouteResult(
                    aliasConfig.getPrimary(),
                    aliasConfig.getFallbacks() != null ? aliasConfig.getFallbacks() : List.of(),
                    modelOrAlias);
        }

        // Direct model name — no fallbacks
        return new RouteResult(modelOrAlias, List.of(), null);
    }

    /**
     * Check if "auto" alias is configured.
     */
    public boolean isAutoAlias(String model) {
        GatewayConfig config = configLoader.getGatewayConfig();
        GatewayConfig.AliasConfig aliasConfig = config.getAliases().get(model);
        return aliasConfig != null && aliasConfig.getClassifier() != null;
    }

    /**
     * Get the auto config for classifier routing.
     */
    public GatewayConfig.AliasConfig getAutoConfig() {
        return configLoader.getGatewayConfig().getAliases().get("auto");
    }
}