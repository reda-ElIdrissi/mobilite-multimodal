package com.transport.bff;

import com.transport.mobility.contracts.v1.Allocation;
import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.QuoteContracts.Selection;
import com.transport.mobility.contracts.v1.SearchRequest;
import com.transport.mobility.search.PlaceDirectory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItineraryComposerTest {
    private static final LocalDate DAY = LocalDate.parse("2026-11-15");
    private static final Instant NOW = Instant.parse("2026-11-15T05:00:00Z");

    @Test
    void directAndParallelVehiclesCoverEveryoneWithDifferentDrivers() {
        var cars = List.of(car(1, "PAR", "VERSAILLES", DAY, DAY.plusDays(1)), car(2, "PAR", "VERSAILLES", DAY, DAY.plusDays(1)));
        var graph = Map.of("PAR", List.of(cars));
        assertEquals(1, solve("PAR", "VERSAILLES", 2, 1, List.of(), graph).get(0).segments().get(0).allocations().size());
        assertTrue(solve("PAR", "VERSAILLES", 4, 1, List.of(), graph).isEmpty());
        assertTrue(solve("PAR", "VERSAILLES", 4, 0, List.of(), graph).isEmpty());

        var selection = solve("PAR", "VERSAILLES", 4, 2, List.of(), graph).get(0);
        var segment = selection.segments().get(0);
        assertEquals(List.of("p1", "p2"), segment.allocations().stream().map(a -> a.driverAlias()).toList());
        assertEquals(Set.of("p1", "p2", "p3", "p4"), segment.allocations().stream()
                .flatMap(a -> a.participantAliases().stream()).collect(Collectors.toSet()));
        var basket = manual().resolve(selection, (operator, query) -> cars);
        assertEquals(List.of(2L, 2L), basket.itinerary().segments().get(0).allocations().stream()
                .map(Allocation::quantity).toList());
    }

    @Test
    void combinesSuccessiveSegmentsAndParallelCars() {
        var first = car(1, "CAS", "RAB", DAY, DAY);
        var second = car(2, "RAB", "FES", DAY, DAY);
        var third = car(3, "RAB", "FES", DAY, DAY);
        var fourth = car(4, "CAS", "RAB", DAY, DAY);
        var graph = Map.of("CAS", List.of(List.of(first, fourth)), "RAB", List.of(List.of(second, third)));
        var series = solve("CAS", "FES", 2, 1, List.of("RAB"), graph).get(0);
        assertEquals(List.of(1, 1), series.segments().stream().map(step -> step.allocations().size()).toList());
        var mixed = solve("CAS", "FES", 4, 2, List.of("RAB"), graph).get(0);
        assertEquals(List.of(2, 2), mixed.segments().stream().map(step -> step.allocations().size()).toList());
        assertEquals(mixed.segments().get(0).plannedArrival().plusMinutes(30), mixed.segments().get(1).plannedDeparture());
        assertEquals(2, manual().resolve(mixed, (operator, query) -> graph.get(query.origin()).get(0))
                .itinerary().segments().size());
    }

    @Test
    void rejectsReuseDuringOverlappingInclusiveDatesAndAllowsDisjointDates() {
        var first = car(1, "CAS", "RAB", DAY, DAY);
        var overlap = car(1, "RAB", "FES", DAY, DAY.plusDays(1));
        var later = car(1, "RAB", "FES", DAY.plusDays(1), DAY.plusDays(1));
        assertTrue(solve("CAS", "FES", 2, 1, List.of("RAB"),
                Map.of("CAS", List.of(List.of(first)), "RAB", List.of(List.of(overlap)))).isEmpty());
        assertEquals(1, solve("CAS", "FES", 2, 1, List.of("RAB"),
                Map.of("CAS", List.of(List.of(first)), "RAB", List.of(List.of(later)))).size());

        var distinct = car(2, "RAB", "FES", DAY, DAY.plusDays(1));
        var accepted = solve("CAS", "FES", 2, 1, List.of("RAB"),
                Map.of("CAS", List.of(List.of(first)), "RAB", List.of(List.of(distinct)))).get(0);
        var segments = accepted.segments();
        var leg = segments.get(1);
        var allocation = leg.allocations().get(0);
        var conflicted = new com.transport.mobility.contracts.v1.QuoteContracts.SelectedAllocation(
                allocation.operator(), overlap.offerId(), allocation.passengerCount(), allocation.participantAliases(),
                allocation.driverAlias(), allocation.startDate(), allocation.endDate());
        var modified = new com.transport.mobility.contracts.v1.QuoteContracts.SelectedSegment(
                1, leg.origin(), leg.destination(), leg.plannedDeparture(), leg.plannedArrival(), List.of(conflicted));
        var manual = new Selection(2, accepted.participants(), List.of(segments.get(0), modified), null);
        assertThrows(IllegalArgumentException.class, () ->
                manual().resolve(manual, (operator, query) -> query.origin().equals("CAS") ? List.of(first) : List.of(overlap)));
    }

    @Test
    void comparesZonedInstantsAndRequiresNinetyMinutesBeforeAir() {
        var train = scheduled(Offer.Operator.RAIL, "CAS", "RAB", "2026-11-15T07:30:00+01:00", "2026-11-15T08:25:00+01:00");
        var tooSoon = scheduled(Offer.Operator.AIR, "RAB", "DAK", "2026-11-15T09:54:00+01:00", "2026-11-15T13:00:00Z");
        var valid = scheduled(Offer.Operator.AIR, "RAB", "DAK", "2026-11-15T09:55:00+01:00", "2026-11-15T13:00:00Z");
        assertTrue(solve("CAS", "DAK", 2, 0, List.of("RAB"),
                Map.of("CAS", List.of(List.of(train)), "RAB", List.of(List.of(tooSoon)))).isEmpty());
        var selected = solve("CAS", "DAK", 2, 0, List.of("RAB"),
                Map.of("CAS", List.of(List.of(train)), "RAB", List.of(List.of(valid)))).get(0);
        var accepted = manual().resolve(selected, (operator, query) -> List.of(operator == Offer.Operator.RAIL ? train : valid));
        assertEquals(2, accepted.itinerary().segments().size());
    }

    @Test
    void reportsTruncationWhenAdditionalCandidatesExist() {
        var request = new SearchRequest("PAR", "VERSAILLES", DAY, null, 2, null, null, 1, null, 1, null);
        var graph = Map.of("PAR", List.of(List.of(
                car(1, "PAR", "VERSAILLES", DAY, DAY), car(2, "PAR", "VERSAILLES", DAY, DAY))));
        var solver = new ItineraryComposer(request, graph, NOW, 1);
        assertEquals(1, solver.solve().size());
        assertTrue(solver.truncated());
    }

    private static SelectionService manual() {
        return new SelectionService(null, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static List<Selection> solve(String from, String to, int travelers, int drivers,
            List<String> via, Map<String, List<List<Offer>>> graph) {
        var request = new SearchRequest(from, to, DAY, null, travelers, null, null, 3, via, drivers, 60);
        return new ItineraryComposer(request, graph, NOW, 20).solve();
    }

    private static Offer car(int id, String from, String to, LocalDate start, LocalDate end) {
        var tuple = id + ":" + to + ":" + start + ":" + end;
        var ref = "car-v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(tuple.getBytes(StandardCharsets.UTF_8));
        return new Offer(Offer.Operator.CAR, Offer.Mode.VOITURE, ref, PlaceDirectory.require(from),
                PlaceDirectory.require(to), null, null, start, end, 30_000, Offer.UnitType.DAY, 1L, 2,
                Offer.AvailabilityKind.VEHICLE_CAPACITY, "Citadine", NOW);
    }

    private static Offer scheduled(Offer.Operator operator, String from, String to, String departure, String arrival) {
        return new Offer(operator, operator == Offer.Operator.AIR ? Offer.Mode.AVION : Offer.Mode.TRAIN,
                from + departure, PlaceDirectory.require(from), PlaceDirectory.require(to), OffsetDateTime.parse(departure),
                OffsetDateTime.parse(arrival), null, null, 10_000, Offer.UnitType.SEAT, null, 2,
                Offer.AvailabilityKind.AVAILABLE_SEATS, null, NOW);
    }
}
