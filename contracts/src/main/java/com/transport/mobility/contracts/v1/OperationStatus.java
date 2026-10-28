package com.transport.mobility.contracts.v1;

import java.util.UUID;

public record OperationStatus(UUID operationId, State state, HoldReceipt hold,
                              ConfirmationReceipt confirmation, CancellationReceipt cancellation,
                              OperatorError error) {
    public enum State { PLANNED, IN_FLIGHT, SUCCEEDED, REJECTED, UNKNOWN, NOT_SENT }
}
