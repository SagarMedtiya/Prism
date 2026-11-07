package com.prism.provider.mock;

import lombok.Getter;

@Getter
public class ProviderException extends RuntimeException {
    private final int statusCode;

    public ProviderException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public boolean isTransient() {
        return statusCode == 429 || statusCode >= 500;
    }

    public boolean isPermanentForModel() {
        return statusCode == 404;
    }

    public boolean isPermanentForRequest() {
        return statusCode == 400 || statusCode == 422;
    }
}