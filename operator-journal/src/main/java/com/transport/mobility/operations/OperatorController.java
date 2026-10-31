package com.transport.mobility.operations;

import com.transport.mobility.contracts.v1.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
class OperatorController {
    private final OperationJournal journal;

    OperatorController(OperationJournal journal) {
        this.journal = journal;
    }

    @PostMapping("/holds")
    ResponseEntity<?> hold(@AuthenticationPrincipal Jwt jwt, @RequestBody HoldCommand command) {
        return response(journal.hold(caller(jwt), command), 201);
    }

    @PostMapping("/holds/{reference}/confirm")
    ResponseEntity<?> confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference,
                              @RequestBody ConfirmCommand command) {
        return response(journal.confirm(caller(jwt), reference, command), 200);
    }

    @PostMapping("/cancellations")
    ResponseEntity<?> cancel(@AuthenticationPrincipal Jwt jwt, @RequestBody CancelCommand command) {
        return response(journal.cancel(caller(jwt), command), 200);
    }

    @GetMapping("/operations/{id}")
    OperationStatus status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return journal.status(caller(jwt), id);
    }

    private static String caller(Jwt jwt) {
        String client = jwt.getClaimAsString("azp");
        String subject = jwt.getSubject();
        if (client == null || client.isBlank() || subject == null || subject.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "SERVICE_IDENTITY_REQUIRED");
        }
        return client + ":" + subject;
    }

    private static ResponseEntity<?> response(OperationStatus status, int success) {
        return switch (status.state()) {
            case SUCCEEDED -> ResponseEntity.status(success).body(status.hold() != null ? status.hold()
                    : status.confirmation() != null ? status.confirmation() : status.cancellation());
            case IN_FLIGHT, PLANNED -> ResponseEntity.accepted()
                    .location(URI.create("/internal/v1/operations/" + status.operationId())).body(status);
            case REJECTED -> ResponseEntity.status(409).body(status.error());
            case UNKNOWN, NOT_SENT -> ResponseEntity.status(503).body(status.error());
        };
    }
}
