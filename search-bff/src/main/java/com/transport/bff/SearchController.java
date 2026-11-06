package com.transport.bff;

import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.OfferSearch;
import com.transport.mobility.contracts.v1.OperatorError;
import com.transport.mobility.contracts.v1.SearchRequest;
import com.transport.mobility.contracts.v1.SearchResult;
import com.transport.mobility.search.PlaceDirectory;
import com.transport.mobility.search.ScheduledOffers;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SearchController {
    private final PartnerClient partners;
    private final ExecutorService workers;
    private final Clock clock;
    private final CompositionSearch composition;

    SearchController(PartnerClient partners, ExecutorService workers, Clock clock, CompositionSearch composition) {
        this.partners = partners;
        this.workers = workers;
        this.clock = clock;
        this.composition = composition;
    }

    @PostMapping("/api/v1/search")
    ResponseEntity<?> search(@RequestBody SearchRequest request) {
        List<Offer.Operator> operators;
        try {
            operators = validate(request);
            if (request.compositionRequested()) {
                CompositionSearch.validate(request);
            }
        } catch (IllegalArgumentException invalid) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, invalid.getMessage());
            problem.setProperty("code", "INVALID_SEARCH");
            return ResponseEntity.badRequest().body(problem);
        }

        String correlationId = UUID.randomUUID().toString();
        String token;
        try {
            token = partners.accessToken();
        } catch (RuntimeException unavailable) {
            return failed(operators, "IDENTITY_UNAVAILABLE", correlationId);
        }

        List<Callable<List<Offer>>> calls = operators.stream().map(operator ->
                (Callable<List<Offer>>) () -> partners.search(operator, toOfferSearch(request, operator), token)).toList();
        try {
            if (request.compositionRequested()) {
                return composition.search(request, operators, token, correlationId);
            }
            var futures = workers.invokeAll(calls, 6, TimeUnit.SECONDS);
            var offers = new ArrayList<Offer>();
            var errors = new EnumMap<Offer.Operator, OperatorError>(Offer.Operator.class);
            boolean truncated = false;
            for (int index = 0; index < operators.size(); index++) {
                var operator = operators.get(index);
                var future = futures.get(index);
                if (future.isCancelled()) {
                    errors.put(operator, error("OPERATOR_TIMEOUT", correlationId));
                    continue;
                }
                try {
                    var found = future.get();
                    truncated |= found.size() >= ScheduledOffers.MAX_OFFERS;
                    offers.addAll(found);
                } catch (ExecutionException unavailable) {
                    errors.put(operator, error("OPERATOR_UNAVAILABLE", correlationId));
                }
            }
            var result = new SearchResult(offers.stream().limit(ScheduledOffers.MAX_OFFERS).toList(), !errors.isEmpty(),
                    truncated || offers.size() > ScheduledOffers.MAX_OFFERS, errors);
            return ResponseEntity.status(errors.size() == operators.size() ? 503 : 200).body(result);
        } catch (RejectedExecutionException busy) {
            return failed(operators, "SEARCH_BUSY", correlationId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(operators, "SEARCH_INTERRUPTED", correlationId);
        }
    }

    private List<Offer.Operator> validate(SearchRequest request) {
        var origin = PlaceDirectory.require(request.origin());
        var destination = PlaceDirectory.require(request.destination());
        int travelers = request.travelerCount() == null ? 1 : request.travelerCount();
        LocalDate start = request.date();
        if (origin.equals(destination) || start == null || start.isBefore(LocalDate.now(clock.withZone(origin.zoneId())))
                || travelers < 1 || travelers > 100 || (request.modes() != null && request.modes().stream().anyMatch(java.util.Objects::isNull))) {
            throw new IllegalArgumentException("Invalid route, date, mode or traveler count");
        }
        if (request.dateFin() != null && (request.dateFin().isBefore(start)
                || ChronoUnit.DAYS.between(start, request.dateFin()) > 30)) {
            throw new IllegalArgumentException("Rental window must contain between 1 and 31 inclusive days");
        }
        var modes = request.modes();
        return java.util.Arrays.stream(Offer.Operator.values())
                .filter(operator -> modes == null || modes.isEmpty() || modes.contains(mode(operator))).toList();
    }

    private static OfferSearch toOfferSearch(SearchRequest request, Offer.Operator operator) {
        boolean car = operator == Offer.Operator.CAR;
        int capacity = car || request.travelerCount() == null ? 1 : request.travelerCount();
        return new OfferSearch(request.origin(), request.destination(), request.date(), car ? request.dateFin() : null,
                capacity, car ? request.category() : null);
    }

    private static Offer.Mode mode(Offer.Operator operator) {
        return switch (operator) {
            case RAIL -> Offer.Mode.TRAIN;
            case AIR -> Offer.Mode.AVION;
            case CAR -> Offer.Mode.VOITURE;
        };
    }

    private static ResponseEntity<SearchResult> failed(List<Offer.Operator> operators, String code, String correlationId) {
        var errors = new EnumMap<Offer.Operator, OperatorError>(Offer.Operator.class);
        operators.forEach(operator -> errors.put(operator, error(code, correlationId)));
        return ResponseEntity.status(503).body(new SearchResult(List.of(), true, false, errors));
    }

    private static OperatorError error(String code, String correlationId) {
        return new OperatorError(code, "No reliable response was obtained", OperatorError.Outcome.UNKNOWN, true, correlationId);
    }
}
