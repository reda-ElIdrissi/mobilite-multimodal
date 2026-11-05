package com.transport.air;

import com.transport.air.ws.*;
import com.transport.mobility.contracts.v1.*;
import com.transport.mobility.operations.Participant;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.ws.soap.client.SoapFaultClientException;

@Component
class AirParticipant implements Participant {
    private final AirClient client;

    AirParticipant(AirClient client) {
        this.client = client;
    }

    @Override
    public HoldReceipt hold(HoldCommand command, Instant startedAt) {
        if (command.seats() == null || command.seats() < 1 || command.car() != null) {
            throw new Rejected("INVALID_SEAT_COUNT");
        }
        var request = new HoldFlightRequest();
        request.setFlightId(command.offerId());
        request.setSeats(command.seats());
        try {
            var response = client.exchange(request, HoldFlightResponse.class);
            return Participant.remoteHold(reference(response.getHoldId()), command, startedAt);
        } catch (SoapFaultClientException fault) {
            String reason = fault.getFaultStringOrReason();
            if (reason != null && (reason.startsWith("Vol inconnu : ") || reason.startsWith("Plus assez de places sur "))) {
                throw new Rejected("NOT_AVAILABLE");
            }
            throw fault;
        }
    }

    @Override
    public ConfirmationReceipt confirm(String reference, ConfirmCommand command) {
        var request = new ConfirmFlightRequest();
        request.setHoldId(reference);
        try {
            var response = client.exchange(request, ConfirmFlightResponse.class);
            return new ConfirmationReceipt(reference(response.getBookingRef()), command.operationId());
        } catch (SoapFaultClientException fault) {
            String reason = fault.getFaultStringOrReason();
            if (reason != null && reason.startsWith("Maintien inconnu ou expiré : ")) {
                throw new Rejected("NOT_AVAILABLE");
            }
            throw fault;
        }
    }

    @Override
    public CancellationReceipt cancel(CancelCommand command) {
        var request = new CancelFlightRequest();
        request.setReference(command.reference());
        String status = client.exchange(request, CancelFlightResponse.class).getStatus();
        String expected = command.referenceKind() == CancelCommand.ReferenceKind.HOLD ? "HOLD_RELEASED" : "BOOKING_CANCELLED";
        if (!expected.equals(status) && !"NOT_FOUND".equals(status)) {
            throw new IllegalStateException("Unrecognized SOAP cancellation result");
        }
        return Participant.cancelled(command);
    }

    private static String reference(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing SOAP reference");
        }
        return value;
    }
}
