package com.transport.mobility.search;

import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.OfferSearch;
import com.transport.mobility.contracts.v1.Place;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

public final class ScheduledOffers {
    public static final int MAX_OFFERS = 100;

    private ScheduledOffers() {}

    public static Leg leg(OfferSearch query) {
        if (query == null || query.date() == null || (query.capacity() != null && query.capacity() < 1)) {
            throw new IllegalArgumentException("Invalid search query");
        }
        return new Leg(PlaceDirectory.require(query.origin()), PlaceDirectory.require(query.destination()),
                query.capacity() == null ? 1 : query.capacity());
    }

    public static Offer toOffer(Offer.Operator operator, Offer.Mode mode, Leg leg, String id,
            String departure, String arrival, long priceMinor, int seats, Instant observedAt) {
        var departureAt = atOffset(departure, leg.origin().zoneId());
        var arrivalAt = atOffset(arrival, leg.destination().zoneId());
        if (priceMinor < 0 || seats < 0 || !arrivalAt.isAfter(departureAt)) {
            throw new IllegalArgumentException("Invalid scheduled offer");
        }
        return new Offer(operator, mode, id, leg.origin(), leg.destination(), departureAt, arrivalAt,
                null, null, priceMinor, Offer.UnitType.SEAT, null, seats, Offer.AvailabilityKind.AVAILABLE_SEATS,
                null, observedAt);
    }

    static OffsetDateTime atOffset(String text, ZoneId zone) {
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException noExplicitOffset) {
            var local = LocalDateTime.parse(text);
            var offsets = zone.getRules().getValidOffsets(local);
            if (offsets.size() != 1) {
                throw new IllegalArgumentException("Ambiguous or nonexistent local time");
            }
            return local.atOffset(offsets.get(0));
        }
    }

    public record Leg(Place origin, Place destination, int capacity) {}
}
