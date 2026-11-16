package com.transport.bff;

import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.QuoteContracts.SelectedAllocation;
import com.transport.mobility.contracts.v1.QuoteContracts.SelectedSegment;
import com.transport.mobility.contracts.v1.QuoteContracts.Selection;
import com.transport.mobility.contracts.v1.SearchRequest;
import com.transport.mobility.search.PlaceDirectory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

final class ItineraryComposer {
    private static final int WORK_LIMIT = 20_000;
    private final SearchRequest request;
    private final Map<String, List<List<Offer>>> offers;
    private final Instant now;
    private final int resultLimit;
    private final List<String> group;
    private final List<Selection> results = new ArrayList<>();
    private final List<SelectedSegment> path = new ArrayList<>();
    private final List<ItineraryRules.CarPeriod> periods = new ArrayList<>();
    private final HashSet<String> visited = new HashSet<>();
    private int remaining = WORK_LIMIT;
    private boolean truncated;

    ItineraryComposer(SearchRequest request, Map<String, List<List<Offer>>> offers, Instant now, int resultLimit) {
        this.request = request;
        this.offers = offers;
        this.now = now;
        this.resultLimit = resultLimit;
        this.group = IntStream.rangeClosed(1, CompositionSearch.travelers(request)).mapToObj(i -> "p" + i).toList();
    }

    List<Selection> solve() {
        visited.add(request.origin());
        // Evaluate direct candidates before spending the search budget on connections.
        if (CompositionSearch.via(request).isEmpty()) {
            walk(request.origin(), true);
        }
        if (!truncated && CompositionSearch.maxSegments(request) > 1) {
            walk(request.origin(), false);
        }
        return List.copyOf(results);
    }

    boolean truncated() {
        return truncated;
    }

    private void walk(String city, boolean directOnly) {
        int index = path.size();
        var via = CompositionSearch.via(request);
        var batches = offers.getOrDefault(city, List.of());
        for (var batch : batches) {
            if (!step()) {
                return;
            }
            Offer first = batch.get(0);
            String next = first.destination().cityCode();
            boolean terminal = next.equals(request.destination());
            if (visited.contains(next) || (index == 0 && via.isEmpty() && terminal != directOnly)
                    || (!via.isEmpty() && !next.equals(index < via.size() ? via.get(index) : request.destination()))
                    || (!terminal && index + 1 == CompositionSearch.maxSegments(request))) {
                continue;
            }

            OffsetDateTime earliest = index == 0 ? now.atZone(first.origin().zoneId()).toOffsetDateTime()
                    : ItineraryRules.connectAfter(path.get(index - 1).plannedArrival(), first.operator());
            if (first.operator() == Offer.Operator.CAR) {
                cars(batch, earliest);
            } else {
                for (var offer : batch) {
                    if (!step()) {
                        return;
                    }
                    if (offer.capacity() >= group.size() && !offer.departureAt().isBefore(earliest)) {
                        extend(List.of(offer), offer.departureAt(), offer.arrivalAt(), List.of());
                    }
                }
            }
        }
    }

    private void cars(List<Offer> batch, OffsetDateTime earliest) {
        int maxCars = Math.min(3, CompositionSearch.drivers(request));
        if (maxCars == 0) {
            return;
        }
        var first = batch.get(0);
        var originZone = first.origin().zoneId();
        if (path.isEmpty()) {
            earliest = earliest.plusMinutes(30);
        }
        var departure = first.startDate().atStartOfDay(originZone).toOffsetDateTime();
        if (earliest.isAfter(departure)) {
            departure = earliest.atZoneSameInstant(originZone).toOffsetDateTime();
        }
        var arrival = departure.plusMinutes(CompositionSearch.carMinutes(request))
                .atZoneSameInstant(first.destination().zoneId()).toOffsetDateTime();
        if (!departure.toLocalDate().equals(first.startDate()) || arrival.toLocalDate().isAfter(first.endDate())) {
            return;
        }
        var vehicles = batch.stream().map(offer -> new Vehicle(offer, ItineraryRules.period(offer)))
                .filter(vehicle -> periods.stream().noneMatch(vehicle.period()::conflicts))
                .sorted(Comparator.comparingInt((Vehicle vehicle) -> vehicle.offer().capacity()).reversed()
                        .thenComparing(vehicle -> vehicle.offer().offerId()))
                .toList();
        chooseCars(vehicles, 0, maxCars, new ArrayList<>(), 0, departure, arrival);
    }

    private void chooseCars(List<Vehicle> available, int start, int maxCars, List<Vehicle> chosen,
            long capacity, OffsetDateTime departure, OffsetDateTime arrival) {
        if (capacity >= group.size()) {
            extend(chosen.stream().map(Vehicle::offer).toList(), departure, arrival,
                    chosen.stream().map(Vehicle::period).toList());
        }
        if (chosen.size() == maxCars) {
            return;
        }
        for (int index = start; index < available.size() && step(); index++) {
            var next = available.get(index);
            if (chosen.stream().anyMatch(vehicle -> vehicle.period().conflicts(next.period()))) {
                continue;
            }
            int remainingSlots = maxCars - chosen.size();
            if (capacity + (long) remainingSlots * next.offer().capacity() < group.size()) {
                break;
            }
            chosen.add(next);
            chooseCars(available, index + 1, maxCars, chosen, capacity + next.offer().capacity(), departure, arrival);
            chosen.remove(chosen.size() - 1);
        }
    }

    private void extend(List<Offer> selected, OffsetDateTime departure, OffsetDateTime arrival,
            List<ItineraryRules.CarPeriod> occupied) {
        var first = selected.get(0);
        String next = first.destination().cityCode();
        var segment = new SelectedSegment(path.size(), first.origin().cityCode(), next, departure, arrival,
                allocate(selected));
        path.add(segment);
        if (next.equals(request.destination())) {
            if (results.size() == resultLimit) {
                truncated = true;
            } else {
                results.add(new Selection(group.size(), group, List.copyOf(path), null));
            }
        } else {
            visited.add(next);
            periods.addAll(occupied);
            walk(next, false);
            periods.subList(periods.size() - occupied.size(), periods.size()).clear();
            visited.remove(next);
        }
        path.remove(path.size() - 1);
    }

    private List<SelectedAllocation> allocate(List<Offer> selected) {
        if (selected.get(0).operator() != Offer.Operator.CAR) {
            var offer = selected.get(0);
            return List.of(new SelectedAllocation(offer.operator(), offer.offerId(), group.size(), group, null, null, null));
        }
        var allocations = new ArrayList<SelectedAllocation>();
        int next = selected.size();
        // Assign one driver to every vehicle before filling the remaining seats.
        for (int index = 0; index < selected.size(); index++) {
            var vehicle = selected.get(index);
            var driver = group.get(index);
            var people = new ArrayList<String>();
            people.add(driver);
            int end = next + Math.min(group.size() - next, vehicle.capacity() - 1);
            people.addAll(group.subList(next, end));
            next = end;
            allocations.add(new SelectedAllocation(vehicle.operator(), vehicle.offerId(), people.size(), people,
                    driver, vehicle.startDate(), vehicle.endDate()));
        }
        return allocations;
    }

    private boolean step() {
        if (remaining-- <= 0) {
            truncated = true;
        }
        return !truncated;
    }

    private record Vehicle(Offer offer, ItineraryRules.CarPeriod period) {}
}
