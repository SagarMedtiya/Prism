package com.prism.config;

import java.time.YearMonth;

public final class RedisKeys {
    private RedisKeys() {
    }

    public static String rpm(long keyId) {
        return "prism:rpm:" + keyId;
    }

    public static String budget(long keyId) {
        YearMonth now = YearMonth.now();
        return "prism:budget:" + keyId + ":" + now;
    }

    public static String budget(long keyId, YearMonth month) {
        return "prism:budget:" + keyId + ":" + month;
    }
}
