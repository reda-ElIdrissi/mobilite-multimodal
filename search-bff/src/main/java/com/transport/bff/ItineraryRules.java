package com.transport.bff;

import com.transport.mobility.contracts.v1.Offer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;

final class ItineraryRules {
    private ItineraryRules() {}

    static OffsetDateTime connectAfter(OffsetDateTime arrival, Offer.Operator next) {
        return arrival.plusMinutes(next == Offer.Operator.AIR ? 90 : 30);
    }

    static CarPeriod period(Offer offer) {
        String prefix = "car-v1.";
        if (!offer.offerId().startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid car offer reference");
        }
        String tuple = new String(Base64.getUrlDecoder().decode(offer.offerId().substring(prefix.length())),
                StandardCharsets.UTF_8);
        return new CarPeriod(tuple.split(":", 2)[0], offer.startDate(), offer.endDate());
    }

    record CarPeriod(String vehicle, LocalDate start, LocalDate end) {
        boolean conflicts(CarPeriod other) {
            return vehicle.equals(other.vehicle) && !start.isAfter(other.end) && !end.isBefore(other.start);
        }
    }
}
