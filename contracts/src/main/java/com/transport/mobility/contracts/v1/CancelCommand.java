package com.transport.mobility.contracts.v1;

import java.util.UUID;

public record CancelCommand(UUID operationId, String reference, ReferenceKind referenceKind,
                            UUID reservationLineId) {
    public enum ReferenceKind { HOLD, BOOKING }
}
