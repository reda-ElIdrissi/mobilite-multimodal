package com.transport.bff;

import com.transport.mobility.contracts.v1.*;
import com.transport.mobility.contracts.v1.QuoteContracts.*;
import com.transport.mobility.search.PlaceDirectory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelectionServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 15);
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-11-15T10:00:00+01:00");
    private static final OffsetDateTime ARRIVAL = DEPARTURE.plusHours(2);
    private static final Offer FIRST = vehicle(1);
    private static final Offer SECOND = vehicle(2);

    private final SelectionService selections = new SelectionService(
            new PartnerClient("", "", "", "", "", "") {
                @Override
                List<Offer> search(Offer.Operator operator, OfferSearch request, String token) {
                    return List.of(FIRST, SECOND);
                }
            }, Clock.fixed(Instant.parse("2026-11-15T05:00:00Z"), ZoneOffset.UTC));

    @Test
    void dividesGroupAcrossCarsAndTakesOnlyObservedPrices() {
        var selected = selection(allocation(FIRST, List.of("p1", "p2"), "p1"),
                allocation(SECOND, List.of("p3", "p4"), "p3"));
        var basket = selections.resolve(selected, "test-token");
        var allocations = basket.itinerary().segments().get(0).allocations();
        assertEquals(2, allocations.size());
        assertEquals(List.of(1L, 1L), allocations.stream().map(Allocation::quantity).toList());
        assertEquals(List.of(30000L, 30000L), allocations.stream().map(a -> a.unitPrice().amountMinor()).toList());
    }

    @Test
    void rejectsOverlapDuplicateParticipantsAndForgedOffer() {
        var first = allocation(FIRST, List.of("p1", "p2"), "p1");
        assertThrows(IllegalArgumentException.class, () -> selections.resolve(selection(first,
                allocation(FIRST, List.of("p3", "p4"), "p3")), "test-token"));
        assertThrows(IllegalArgumentException.class, () -> selections.resolve(selection(first,
                allocation(SECOND, List.of("p1", "p4"), "p1")), "test-token"));
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> selections.resolve(selection(first, allocation(vehicle(3), List.of("p3", "p4"), "p3")),
                        "test-token")).getStatusCode().value());
    }

    private static Selection selection(SelectedAllocation first, SelectedAllocation second) {
        return new Selection(4, List.of("p1", "p2", "p3", "p4"), List.of(
                new SelectedSegment(0, "PAR", "VERSAILLES", DEPARTURE, ARRIVAL, List.of(first, second))), null);
    }

    private static SelectedAllocation allocation(Offer offer, List<String> people, String driver) {
        return new SelectedAllocation(offer.operator(), offer.offerId(), people.size(), people, driver, DATE, DATE);
    }

    private static Offer vehicle(int id) {
        String tuple = id + ":2:" + DATE + ":" + DATE;
        return new Offer(Offer.Operator.CAR, Offer.Mode.VOITURE,
                "car-v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(tuple.getBytes(StandardCharsets.UTF_8)),
                PlaceDirectory.require("PAR"), PlaceDirectory.require("VERSAILLES"), null, null, DATE, DATE,
                30000, Offer.UnitType.DAY, 0L, 2, Offer.AvailabilityKind.VEHICLE_CAPACITY, "Citadine", DEPARTURE.toInstant());
    }
}
