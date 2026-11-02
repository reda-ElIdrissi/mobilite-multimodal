package com.transport.autopartage.vehiculeRoute;

import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class CarHandoverController {
    private final CarParticipant participant;

    CarHandoverController(CarParticipant participant) {
        this.participant = participant;
    }

    @PostMapping("/api/v1/vehicules/{id}/handover")
    Handover handover(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody Request request) {
        return participant.handover(id, VehiculeController.subject(jwt), request.locationId(), request.city());
    }

    record Request(long locationId, String city) {}
    record Handover(long locationId, String city, Instant handedOverAt) {}
}
