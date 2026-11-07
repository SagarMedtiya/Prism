package com.prism.api;

import com.prism.provider.mock.MockProviderA;
import com.prism.provider.mock.MockProviderB;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/mock")
@RequiredArgsConstructor
public class MockControlController {

    private final MockProviderA providerA;
    private final MockProviderB providerB;

    @PostMapping("/{provider}/down")
    public Map<String, String> setDown(@PathVariable String provider,
            @RequestParam boolean value) {
        if ("provider-b".equals(provider)) {
            providerB.setDown(value);
        } else {
            providerA.setDown(value);
        }
        return Map.of("provider", provider, "down", String.valueOf(value));
    }

    @PostMapping("/{provider}/error-rate")
    public Map<String, String> setErrorRate(@PathVariable String provider,
            @RequestParam int percent) {
        if ("provider-b".equals(provider)) {
            providerB.setErrorRate(percent);
        } else {
            providerA.setErrorRate(percent);
        }
        return Map.of("provider", provider, "errorRate", percent + "%");
    }

    @PostMapping("/{provider}/latency")
    public Map<String, String> setLatency(@PathVariable String provider,
            @RequestParam int ms) {
        if ("provider-b".equals(provider)) {
            providerB.setLatencyMs(ms);
        } else {
            providerA.setLatencyMs(ms);
        }
        return Map.of("provider", provider, "latencyMs", String.valueOf(ms));
    }
}
