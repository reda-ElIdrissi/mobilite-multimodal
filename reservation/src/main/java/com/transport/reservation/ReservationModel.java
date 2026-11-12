package com.transport.reservation;

import com.transport.mobility.contracts.v1.Allocation;
import com.transport.mobility.contracts.v1.Money;
import com.transport.mobility.contracts.v1.QuoteContracts.Price;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class ReservationModel {
    enum Status {
        PENDING_QUOTE, HOLDING, CONFIRMING, CONFIRMED, COMPENSATING,
        CANCELLING, CANCELLED, FAILED_COMPENSATED, NEEDS_RECONCILIATION
    }

    enum Action { CLAIM, VALIDATE, HOLD, CONFIRM, CANCEL }
    enum State { IN_FLIGHT, SUCCEEDED, REJECTED, UNKNOWN, NOT_SENT, RELEASED }

    record Case(UUID id, UUID quote, String subject, Status status, Price snapshot, Instant created,
                Instant quoteExpires, Instant firstDeparture, Instant deadline, Instant cancelRequested, boolean everConfirmed, String failure, long version, String traceparent) {}
    record Line(UUID id, int position, int segment, Allocation allocation) {}
    record Step(UUID id, UUID line, Action action, State state, String reference, Result result, int attempts, Instant updated) {}
    record Result(State state, String reference, Instant expires, String code) {
        static Result success(String reference, Instant expires) {
            return new Result(State.SUCCEEDED, reference, expires, null);
        }
        static Result state(State state, String code) {
            return new Result(state, null, null, code);
        }
    }
    record Lease(UUID id, UUID token) {}
    record Evidence(UUID operation, Instant safeExpiry, Instant resolvedAt) {}
    record View(UUID id, UUID devisId, Status status, Money total, List<LineView> lines,
                String failureCode, Instant createdAt, long version) {}
    record LineView(UUID id, int segmentIndex, Allocation allocation, com.transport.mobility.contracts.v1.QuoteContracts.Line price, String holdId, String bookingRef, String state) {}

    private ReservationModel() {}
}
