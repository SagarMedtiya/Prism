package com.prism.routing;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class RouteResult {
    private String primaryModel;
    private List<String> fallbacks;
    private String resolvedAlias; // the alias that was resolved, null if direct model
}