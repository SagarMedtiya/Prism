package com.prism.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class GatewayConfig {

    private Map<String, AliasConfig> aliases;
    private Map<String, ProviderConfig> providers;

    @Data
    public static class AliasConfig {
        private String primary;
        private List<String> fallbacks;
        private String classifier;
        private Map<String, String> routes;
    }

    @Data
    public static class ProviderConfig {
        private String type;
        private List<String> models;
        private String baseUrl;
    }
}