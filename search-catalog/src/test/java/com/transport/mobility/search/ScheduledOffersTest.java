package com.transport.mobility.search;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduledOffersTest {
    @Test
    void usesOriginAndDestinationZonesIndependently() {
        var casablanca = PlaceDirectory.require("CAS").zoneId();
        var departure = LocalDateTime.parse("2026-11-15T15:00:00");
        assertEquals(departure.atZone(casablanca).toOffsetDateTime(),
                ScheduledOffers.atOffset(departure.toString(), casablanca));
        assertEquals(OffsetDateTime.parse("2026-11-15T18:00:00Z"),
                ScheduledOffers.atOffset("2026-11-15T18:00:00", PlaceDirectory.require("DAK").zoneId()));
    }

    @Test
    void honorsExplicitOffsetAndRejectsUnresolvedDstTransitions() {
        var paris = ZoneId.of("Europe/Paris");
        assertEquals(OffsetDateTime.parse("2026-10-25T02:30:00+01:00"),
                ScheduledOffers.atOffset("2026-10-25T02:30:00+01:00", paris));
        assertThrows(IllegalArgumentException.class, () -> ScheduledOffers.atOffset("2026-10-25T02:30:00", paris));
        assertThrows(IllegalArgumentException.class, () -> ScheduledOffers.atOffset("2026-03-29T02:30:00", paris));
    }
}
