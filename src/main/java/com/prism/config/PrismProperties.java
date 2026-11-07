package com.prism.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "prism")
public class PrismProperties {

    private Providers providers = new Providers();
    private Cache cache = new Cache();
    private Budget budget = new Budget();

    @Data
    public static class Providers {
        private Timeout timeout = new Timeout();
        private Retry retry = new Retry();

        @Data
        public static class Timeout {
            private int connectMs = 5000;
            private int firstByteMs = 10000;
            private int totalMs = 30000;
        }

        @Data
        public static class Retry {
            private int maxAttempts = 3;
            private int initialDelayMs = 500;
            private int maxDelayMs = 5000;
        }
    }

    @Data
    public static class Cache {
        private double similarityThreshold = 0.92;
        private int ttlMinutes = 60;
        private int maxEntriesPerKey = 100;
    }

    @Data
    public static class Budget {
        private int reservationTtlSeconds = 120;
    }
}
