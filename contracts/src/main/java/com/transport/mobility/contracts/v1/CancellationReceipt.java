package com.transport.mobility.contracts.v1;

import java.util.UUID;

public record CancellationReceipt(UUID operationId, Status status) {
    public enum Status { CANCELLED }
}
