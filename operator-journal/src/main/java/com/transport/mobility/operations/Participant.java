package com.transport.mobility.operations;

import com.transport.mobility.contracts.v1.*;
import java.time.Duration;
import java.time.Instant;

public interface Participant {
    HoldReceipt hold(HoldCommand command, Instant startedAt);
    ConfirmationReceipt confirm(String reference, ConfirmCommand command);
    CancellationReceipt cancel(CancelCommand command);

    default boolean remote() {
        return true;
    }

    static HoldReceipt remoteHold(String reference, HoldCommand command, Instant startedAt) {
        return new HoldReceipt(reference, startedAt.plus(Duration.ofMinutes(10)), command.operationId());
    }

    static CancellationReceipt cancelled(CancelCommand command) {
        return new CancellationReceipt(command.operationId(), CancellationReceipt.Status.CANCELLED);
    }

    final class Rejected extends RuntimeException {
        public Rejected(String code) {
            super(code);
        }
    }

    final class NotSent extends RuntimeException {
        public NotSent(String code) {
            super(code);
        }
    }
}
