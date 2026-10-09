package com.retailiq.api.ml;

public class MlIntegrationException extends RuntimeException {
    private final int status;
    public MlIntegrationException(String message, int status, Throwable cause) {
        super(message, cause); this.status = status;
    }
    public int getStatus() { return status; }
}
