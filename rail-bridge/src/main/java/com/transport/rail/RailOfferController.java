package com.transport.rail;

import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.OfferSearch;
import com.transport.mobility.contracts.v1.OperatorError;
import com.transport.mobility.search.ScheduledOffers;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RailOfferController {
    private final RailClient client;

    RailOfferController(RailClient client) {
        this.client = client;
    }

    @PostMapping("/internal/v1/offers/search")
    ResponseEntity<?> search(@RequestBody OfferSearch request) {
        ScheduledOffers.Leg leg;
        try {
            leg = ScheduledOffers.leg(request);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("status", 400, "code", "INVALID_SEARCH",
                    "message", invalid.getMessage(), "fieldErrors", Map.of()));
        }
        try {
            var observedAt = Instant.now();
            var journeys = client.search(leg.origin().displayName(), leg.destination().displayName(), request.date());
            return ResponseEntity.ok(Arrays.stream(journeys)
                    .filter(journey -> journey.seatsAvailable >= leg.capacity())
                    .limit(ScheduledOffers.MAX_OFFERS)
                    .map(journey -> ScheduledOffers.toOffer(Offer.Operator.RAIL, Offer.Mode.TRAIN,
                            leg, journey.tripId, journey.departure, journey.arrival, journey.priceMinor, journey.seatsAvailable,
                            observedAt))
                    .toList());
        } catch (java.io.IOException | RuntimeException unavailable) {
            return ResponseEntity.status(503).body(new OperatorError("OPERATOR_UNAVAILABLE",
                    "Rail operator did not provide a valid response", OperatorError.Outcome.UNKNOWN,
                    true, UUID.randomUUID().toString()));
        }
    }
}
