package com.transport.rail;

import com.transport.mobility.contracts.v1.*;
import com.transport.mobility.operations.Participant;
import com.transport.mobility.observability.Telemetry;
import java.io.IOException;
import java.time.Instant;
import mobility.rail.NotAvailable;
import mobility.rail.RailBooking;
import org.springframework.stereotype.Component;

@Component
class RailParticipant implements Participant {
    private final RailClient client;

    RailParticipant(RailClient client) {
        this.client = client;
    }

    @Override
    public HoldReceipt hold(HoldCommand command, Instant startedAt) {
        if (command.seats() == null || command.seats() < 1 || command.car() != null) {
            throw new Rejected("INVALID_SEAT_COUNT");
        }
        var rail = resolve();
        try {
            return Participant.remoteHold(reference(Telemetry.legacy("RAIL", "iiop", "hold", () -> rail.hold(command.offerId(), command.seats()))), command, startedAt);
        } catch (NotAvailable rejected) {
            throw new Rejected("NOT_AVAILABLE");
        }
    }

    @Override
    public ConfirmationReceipt confirm(String reference, ConfirmCommand command) {
        var rail = resolve();
        try {
            return new ConfirmationReceipt(reference(Telemetry.legacy("RAIL", "iiop", "confirm", () -> rail.confirm(reference))), command.operationId());
        } catch (NotAvailable rejected) {
            throw new Rejected("NOT_AVAILABLE");
        }
    }

    @Override
    public CancellationReceipt cancel(CancelCommand command) {
        Telemetry.legacy("RAIL", "iiop", "cancel", () -> {
            resolve().cancel(command.reference());
            return null;
        });
        return Participant.cancelled(command);
    }

    private RailBooking resolve() {
        try {
            return client.reference();
        } catch (IOException | RuntimeException unavailable) {
            throw new NotSent("RAIL_REFERENCE_UNAVAILABLE");
        }
    }

    private static String reference(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing rail reference");
        }
        return value;
    }
}
