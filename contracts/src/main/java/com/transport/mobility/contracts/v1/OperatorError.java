package com.transport.mobility.contracts.v1;

public record OperatorError(String code, String message, Outcome outcome, boolean retryable, String correlationId) {
    public enum Outcome { REJECTED, UNKNOWN }
}
