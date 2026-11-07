package com.transport.mobility.events;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

public record ReservationEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
        UUID reservationId, long aggregateVersion, String userId, UUID correlationId, UUID causationId, Payload payload) {
    public ReservationEvent {
        if (eventId == null || eventType == null || schemaVersion != 1 || occurredAt == null
                || reservationId == null || aggregateVersion < 1 || userId == null || userId.isBlank()
                || userId.length() > 255 || payload == null || !payload.status().eventType.equals(eventType)) {
            throw new IllegalArgumentException("Invalid reservation event envelope");
        }
    }

    public record Payload(UUID devisId, Status reservationStatus, QuoteOutcome quoteOutcome,
                          boolean everConfirmed, String failureCode) {
        public Payload {
            if (devisId == null || reservationStatus == null || quoteOutcome == null
                    || everConfirmed != (quoteOutcome == QuoteOutcome.CONSUMED)
                    || reservationStatus == Status.CONFIRMED && !everConfirmed
                    || quoteOutcome == QuoteOutcome.VOID
                        && reservationStatus != Status.CANCELLED && reservationStatus != Status.FAILED_COMPENSATED) {
                throw new IllegalArgumentException("Invalid reservation event outcome");
            }
        }

        public Status status() { return reservationStatus; }
    }

    public record Outcome(UUID reservationId, UUID devisId, String reservationStatus, QuoteOutcome quoteOutcome,
                          boolean everConfirmed, String failureCode, long aggregateVersion) {}

    public enum QuoteOutcome { UNCHANGED, VOID, CONSUMED }

    public enum Status {
        CONFIRMED("reservation.confirmed.v1"),
        CANCELLED("reservation.cancelled.v1"),
        FAILED_COMPENSATED("reservation.failed-compensated.v1"),
        NEEDS_RECONCILIATION("reservation.reconciliation-required.v1");

        private final String eventType;
        Status(String eventType) { this.eventType = eventType; }
    }

    public static ReservationEvent read(JsonMapper json, byte[] body, String routingKey) {
        var event = json.readValue(body, ReservationEvent.class);
        if (event == null || !event.eventType().equals(routingKey)) {
            throw new IllegalArgumentException("Reservation routing key mismatch");
        }
        return event;
    }
}
