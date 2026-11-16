package com.transport.bff;

import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.OfferSearch;
import com.transport.mobility.contracts.v1.OperatorError;
import com.transport.mobility.contracts.v1.QuoteContracts.PreviewBatch;
import com.transport.mobility.contracts.v1.QuoteContracts.Selection;
import com.transport.mobility.contracts.v1.SearchRequest;
import com.transport.mobility.contracts.v1.SearchResult;
import com.transport.mobility.contracts.v1.SearchResult.Candidate;
import com.transport.mobility.contracts.v1.SearchResult.ObservedAllocation;
import com.transport.mobility.search.PlaceDirectory;
import com.transport.mobility.search.ScheduledOffers;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
class CompositionSearch {
    private static final int RESULT_LIMIT = 20;
    private final PartnerClient partners;
    private final ExecutorService workers;
    private final SelectionService selections;
    private final Clock clock;
    private final int callLimit;
    private final int parallelism;
    private final List<String> hubs;

    CompositionSearch(PartnerClient partners, ExecutorService workers, SelectionService selections, Clock clock,
            @Value("${mobility.composition-max-calls:96}") int callLimit,
            @Value("${mobility.composition-parallelism:6}") int parallelism,
            @Value("${mobility.composition-hubs:RAB,PAR}") String hubs) {
        if (callLimit < 24 || callLimit > 432) {
            throw new IllegalArgumentException("Composition call limit must be between 24 and 432");
        }
        if (parallelism < 1 || parallelism > 9) {
            throw new IllegalArgumentException("Composition parallelism must be between 1 and 9");
        }
        this.partners = partners;
        this.workers = workers;
        this.selections = selections;
        this.clock = clock;
        this.callLimit = callLimit;
        this.parallelism = parallelism;
        this.hubs = Arrays.stream(hubs.split(",")).map(String::trim).filter(code -> !code.isEmpty())
                .map(code -> PlaceDirectory.require(code).cityCode()).distinct().toList();
    }

    static void validate(SearchRequest request) {
        var via = via(request);
        int segments = maxSegments(request);
        int drivers = drivers(request);
        int duration = carMinutes(request);
        if (segments < 1 || segments > 3 || via.size() >= segments || drivers < 0 || drivers > travelers(request)
                || duration < 1 || duration > 1440 || request.date().getYear() >= 9999) {
            throw new IllegalArgumentException("Invalid composition bounds");
        }
        var distinct = new HashSet<>(List.of(request.origin(), request.destination()));
        for (String city : via) {
            PlaceDirectory.require(city);
            if (!distinct.add(city)) {
                throw new IllegalArgumentException("Via cities must be distinct from each other and the endpoints");
            }
        }
    }

    ResponseEntity<SearchResult> search(SearchRequest request, List<Offer.Operator> operators, String token,
            String correlationId) throws InterruptedException {
        var plan = plan(request, operators);
        var discovery = discover(plan.stream().limit(callLimit).toList(), token, correlationId);
        var direct = new ArrayList<Offer>();
        var graph = new HashMap<String, List<List<Offer>>>();
        for (var lookup : plan) {
            var found = discovery.offers.getOrDefault(lookup, List.of());
            if (found.isEmpty()) {
                continue;
            }
            var query = lookup.query();
            graph.computeIfAbsent(query.origin(), ignored -> new ArrayList<>()).add(found);
            if (query.origin().equals(request.origin()) && query.destination().equals(request.destination())
                    && query.date().equals(request.date())) {
                direct.addAll(found);
            }
        }

        var solver = new ItineraryComposer(request, graph, clock.instant(), RESULT_LIMIT);
        var solutions = solver.solve();
        var priced = price(solutions, discovery, token);
        boolean partial = !discovery.errors.isEmpty() || priced.error() != null;
        boolean truncated = plan.size() > callLimit || discovery.truncated || solver.truncated()
                || direct.size() > ScheduledOffers.MAX_OFFERS;
        var result = new SearchResult(direct.stream().limit(ScheduledOffers.MAX_OFFERS).toList(), partial, truncated,
                discovery.errors, priced.candidates(), List.of(
                        "Discovery uses the city catalogue and two dates per scheduled route.",
                        "Connections allow 90 min towards air and 30 min otherwise.",
                        "Car travel time is indicative (" + carMinutes(request) + " min). Rental dates are inclusive.",
                        "An initial car segment starts at least 30 min after the search.",
                        "Only the first " + drivers(request) + " participant aliases are eligible to drive."),
                priced.error());
        return ResponseEntity.status(discovery.offers.isEmpty() ? 503 : 200).body(result);
    }

    private List<Lookup> plan(SearchRequest request, List<Offer.Operator> operators) {
        var routes = new LinkedHashSet<Route>();
        String origin = request.origin();
        String destination = request.destination();
        var priority = new LinkedHashSet<Route>();
        priority.add(new Route(origin, destination));
        if (!via(request).isEmpty()) {
            addRoute(priority, origin, via(request), destination);
        } else if (maxSegments(request) > 1) {
            var cities = new LinkedHashSet<>(hubs);
            cities.addAll(PlaceDirectory.cityCodes());
            cities.remove(origin);
            cities.remove(destination);
            if (maxSegments(request) == 3) {
                for (String first : hubs) {
                    for (String second : hubs) {
                        if (!first.equals(second) && cities.contains(first) && cities.contains(second)) {
                            addRoute(priority, origin, List.of(first, second), destination);
                        }
                    }
                }
            }
            for (String city : cities) {
                addRoute(routes, origin, List.of(city), destination);
            }
            if (maxSegments(request) == 3) {
                for (String first : cities) {
                    for (String second : cities) {
                        if (!first.equals(second)) {
                            routes.add(new Route(first, second));
                        }
                    }
                }
            }
        }
        routes.removeAll(priority);
        var calls = new ArrayList<Lookup>();
        addCalls(calls, priority, request, operators);
        addCalls(calls, routes, request, operators);
        return calls;
    }

    private void addCalls(List<Lookup> calls, LinkedHashSet<Route> routes, SearchRequest request,
            List<Offer.Operator> operators) {
        for (LocalDate date : List.of(request.date(), request.date().plusDays(1))) {
            for (Route route : routes) {
                if (date.isBefore(LocalDate.now(clock.withZone(PlaceDirectory.require(route.origin()).zoneId())))) {
                    continue;
                }
                for (Offer.Operator operator : operators) {
                    boolean car = operator == Offer.Operator.CAR;
                    LocalDate end = car ? request.dateFin() : null;
                    if (end == null || !end.isBefore(date)) {
                        calls.add(new Lookup(operator, new OfferSearch(route.origin(), route.destination(), date, end,
                                car ? 1 : travelers(request), car ? request.category() : null)));
                    }
                }
            }
        }
    }

    private static void addRoute(LinkedHashSet<Route> routes, String origin, List<String> via, String destination) {
        String previous = origin;
        for (String city : via) {
            routes.add(new Route(previous, city));
            previous = city;
        }
        routes.add(new Route(previous, destination));
    }

    private Discovery discover(List<Lookup> plan, String token, String correlationId) throws InterruptedException {
        var result = new Discovery(correlationId);
        var pending = new HashMap<Future<List<Offer>>, Lookup>();
        CompletionService<List<Offer>> completion = new ExecutorCompletionService<>(workers);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        int next = 0;
        try {
            while (next < plan.size() || !pending.isEmpty()) {
                while (pending.size() < parallelism && next < plan.size() && System.nanoTime() < deadline) {
                    var key = plan.get(next++);
                    try {
                        var future = completion.submit(() -> partners.search(key.operator(), key.query(), token));
                        pending.put(future, key);
                    } catch (RejectedExecutionException busy) {
                        result.fail(key, "SEARCH_BUSY");
                    }
                }
                if (pending.isEmpty()) {
                    break;
                }
                long remaining = Math.max(0, deadline - System.nanoTime());
                var future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) {
                    break;
                }
                var key = pending.remove(future);
                try {
                    var found = future.get();
                    result.truncated |= found.size() >= ScheduledOffers.MAX_OFFERS;
                    var unique = new LinkedHashMap<String, Offer>();
                    found.stream().limit(ScheduledOffers.MAX_OFFERS).filter(offer -> matches(key, offer))
                            .forEach(offer -> unique.putIfAbsent(offer.offerId(), offer));
                    result.offers.put(key, List.copyOf(unique.values()));
                } catch (ExecutionException unavailable) {
                    result.fail(key, "OPERATOR_UNAVAILABLE");
                }
            }
            pending.values().forEach(key -> result.fail(key, "OPERATOR_TIMEOUT"));
            plan.subList(next, plan.size()).forEach(key -> result.fail(key, "OPERATOR_TIMEOUT"));
            return result;
        } finally {
            pending.keySet().forEach(future -> future.cancel(true));
        }
    }

    private static boolean matches(Lookup key, Offer offer) {
        var query = key.query();
        if (offer.origin() == null || offer.destination() == null || offer.offerId() == null
                || !query.origin().equals(offer.origin().cityCode()) || !query.destination().equals(offer.destination().cityCode())
                || offer.capacity() < query.capacity()) {
            return false;
        }
        if (key.operator() == Offer.Operator.CAR) {
            return query.date().equals(offer.startDate())
                    && (query.dateFin() == null ? query.date() : query.dateFin()).equals(offer.endDate());
        }
        return offer.departureAt() != null && offer.arrivalAt() != null
                && offer.arrivalAt().isAfter(offer.departureAt())
                && query.date().equals(offer.departureAt().atZoneSameInstant(offer.origin().zoneId()).toLocalDate());
    }

    private Priced price(List<Selection> selectionsFound, Discovery discovery, String token) {
        if (selectionsFound.isEmpty()) {
            return new Priced(List.of(), null);
        }
        var byRoute = new HashMap<Lookup, List<Offer>>();
        var observed = new HashMap<OfferReference, Offer>();
        discovery.offers.forEach((key, offers) -> {
            var query = key.query();
            var normalized = new OfferSearch(query.origin(), query.destination(), query.date(),
                    key.operator() == Offer.Operator.CAR && query.dateFin() == null ? query.date() : query.dateFin(), null, null);
            byRoute.put(new Lookup(key.operator(), normalized), offers);
            offers.forEach(offer -> observed.put(new OfferReference(offer.operator(), offer.offerId()), offer));
        });
        var candidates = selectionsFound.stream().map(selection -> new Candidate(selection, null,
                selection.segments().stream().flatMap(segment -> segment.allocations().stream().map(allocation -> {
                    var offer = observed.get(new OfferReference(allocation.operator(), allocation.offerId()));
                    return new ObservedAllocation(segment.segmentIndex(), offer.operator(), offer.offerId(),
                            offer.capacity(), offer.availabilityKind(), offer.category());
                })).toList())).toList();
        var baskets = selectionsFound.stream().map(selection -> selections.resolve(selection, (operator, query) ->
                byRoute.getOrDefault(new Lookup(operator, new OfferSearch(query.origin(), query.destination(),
                        query.date(), query.dateFin(), null, null)), List.of()))).toList();
        try {
            var prices = partners.preview(new PreviewBatch(baskets), token);
            if (prices != null && prices.size() == candidates.size()
                    && prices.stream().allMatch(price -> price != null && price.total() != null)) {
                var result = new ArrayList<Candidate>();
                for (int i = 0; i < candidates.size(); i++) {
                    result.add(candidates.get(i).withEstimate(prices.get(i).total()));
                }
                result.sort(Comparator.comparingLong((Candidate candidate) -> candidate.estimatedTotal().amountMinor())
                        .thenComparing(candidate -> travelTime(candidate.selection())));
                return new Priced(result, null);
            }
        } catch (RestClientException unavailable) {
            // Offer discovery succeeded, so still return the selections without estimates.
        }
        return new Priced(candidates, "PRICING_UNAVAILABLE");
    }

    private static Duration travelTime(Selection candidate) {
        var segments = candidate.segments();
        return Duration.between(segments.get(0).plannedDeparture(), segments.get(segments.size() - 1).plannedArrival());
    }

    static int maxSegments(SearchRequest request) {
        return request.maxSegments() == null ? 3 : request.maxSegments();
    }

    static int travelers(SearchRequest request) {
        return request.travelerCount() == null ? 1 : request.travelerCount();
    }

    static int drivers(SearchRequest request) {
        return request.driverCount() == null ? travelers(request) : request.driverCount();
    }

    static int carMinutes(SearchRequest request) {
        return request.carDurationMinutes() == null ? 60 : request.carDurationMinutes();
    }

    static List<String> via(SearchRequest request) {
        return request.via() == null ? List.of() : request.via();
    }

    private record Route(String origin, String destination) {}
    private record Lookup(Offer.Operator operator, OfferSearch query) {}
    private record OfferReference(Offer.Operator operator, String id) {}
    private record Priced(List<Candidate> candidates, String error) {}

    private static final class Discovery {
        private final Map<Lookup, List<Offer>> offers = new HashMap<>();
        private final Map<Offer.Operator, OperatorError> errors = new EnumMap<>(Offer.Operator.class);
        private final String correlationId;
        private boolean truncated;

        Discovery(String correlationId) {
            this.correlationId = correlationId;
        }

        void fail(Lookup key, String code) {
            errors.putIfAbsent(key.operator(), new OperatorError(code, "One or more route lookups failed",
                    OperatorError.Outcome.UNKNOWN, true, correlationId));
        }
    }
}
