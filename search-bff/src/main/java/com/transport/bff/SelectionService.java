package com.transport.bff;

import com.transport.mobility.contracts.v1.*;
import com.transport.mobility.contracts.v1.QuoteContracts.*;
import com.transport.mobility.search.PlaceDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
class SelectionService {
    private final PartnerClient partners;
    private final Clock clock;

    SelectionService(PartnerClient partners, Clock clock) {
        this.partners = partners;
        this.clock = clock;
    }

    Basket resolve(Selection selection, String token) {
        return resolve(selection, (operator, request) -> search(new Lookup(operator, request), token));
    }

    Basket resolve(Selection selection, java.util.function.BiFunction<Offer.Operator, OfferSearch, List<Offer>> lookup) {
        if (selection.travelerCount() < 1 || selection.travelerCount() > 100
                || selection.participants() == null || selection.segments() == null
                || selection.segments().isEmpty() || selection.segments().size() > 3) {
            throw invalid("Invalid group or itinerary size");
        }
        var group = new HashSet<>(selection.participants());
        if (group.size() != selection.travelerCount() || group.size() != selection.participants().size()
                || group.stream().anyMatch(alias -> alias == null || !alias.matches("[A-Za-z0-9_-]{1,32}"))) {
            throw invalid("Participant aliases must be unique");
        }
        var segments = new ArrayList<Segment>();
        var categories = new HashMap<UUID, String>();
        var carPeriods = new HashMap<String, List<ItineraryRules.CarPeriod>>();
        var searches = new HashMap<Lookup, List<Offer>>();
        for (SelectedSegment leg : selection.segments()) {
            if (leg == null || leg.segmentIndex() != segments.size() || leg.allocations() == null
                    || leg.allocations().isEmpty() || leg.allocations().size() > 3) {
                throw invalid("Invalid segment index or allocation count");
            }
            var origin = PlaceDirectory.require(leg.origin());
            var destination = PlaceDirectory.require(leg.destination());
            var used = new HashSet<String>();
            var drivers = new HashSet<String>();
            var allocations = new ArrayList<Allocation>();
            boolean car = leg.allocations().get(0) != null && leg.allocations().get(0).operator() == Offer.Operator.CAR;
            if (!car && leg.allocations().size() != 1) {
                throw invalid("A scheduled segment has one allocation");
            }
            OffsetDateTime departure = leg.plannedDeparture();
            OffsetDateTime arrival = leg.plannedArrival();
            if (departure == null || arrival == null || !arrival.isAfter(departure)
                    || departure.toInstant().isBefore(clock.instant()) || origin.equals(destination)) {
                throw invalid("Invalid travel window");
            }
            for (SelectedAllocation requested : leg.allocations()) {
                if (requested == null || requested.operator() == null || requested.offerId() == null
                        || (requested.operator() == Offer.Operator.CAR) != car || requested.participantAliases() == null
                        || requested.passengerCount() < 1 || requested.passengerCount() != requested.participantAliases().size()
                        || requested.participantAliases().stream().anyMatch(alias -> !group.contains(alias) || !used.add(alias))) {
                    throw invalid("Invalid participant allocation");
                }
                LocalDate date = departure.atZoneSameInstant(origin.zoneId()).toLocalDate();
                if (car) {
                    verifyCar(requested, drivers, date, arrival, destination);
                } else if (requested.driverAlias() != null || requested.startDate() != null || requested.endDate() != null) {
                    throw invalid("Car details on a scheduled service");
                }
                var request = new OfferSearch(origin.cityCode(), destination.cityCode(), date,
                        requested.endDate(), requested.passengerCount(), null);
                var key = new Lookup(requested.operator(), request);
                var offer = searches.computeIfAbsent(key, ignored -> lookup.apply(key.operator(), key.request())).stream()
                        .filter(candidate -> requested.offerId().equals(candidate.offerId())
                                && candidate.capacity() >= requested.passengerCount()
                                && origin.equals(candidate.origin()) && destination.equals(candidate.destination()))
                        .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "OFFER_NOT_AVAILABLE"));
                if (car) {
                    if (!date.equals(offer.startDate()) || !requested.endDate().equals(offer.endDate())) {
                        throw invalid("Car dates do not match the offer");
                    }
                    var period = ItineraryRules.period(offer);
                    var occupied = carPeriods.computeIfAbsent(period.vehicle(), ignored -> new ArrayList<>());
                    if (occupied.stream().anyMatch(period::conflicts)) {
                        throw invalid("Car is allocated more than once during overlapping dates");
                    }
                    occupied.add(period);
                } else {
                    if (offer.departureAt() == null || offer.arrivalAt() == null
                            || !departure.isEqual(offer.departureAt()) || !arrival.isEqual(offer.arrivalAt())) {
                        throw invalid("Scheduled times do not match the offer");
                    }
                    departure = offer.departureAt();
                    arrival = offer.arrivalAt();
                }
                UUID id = UUID.randomUUID();
                if (offer.category() != null) {
                    categories.put(id, offer.category());
                }
                long quantity = car ? ChronoUnit.DAYS.between(offer.startDate(), offer.endDate()) + 1 : requested.passengerCount();
                allocations.add(new Allocation(id, offer.operator(), offer.offerId(), offer.mode(), requested.passengerCount(),
                        quantity, offer.unitType(), departure, arrival, offer.startDate(), offer.endDate(),
                        requested.participantAliases(), requested.driverAlias(),
                        new Money(offer.unitPriceMinor(), Money.Currency.MAD), offer.priceVersion()));
            }
            if (!used.equals(group)) {
                throw invalid("Each segment must carry the entire group");
            }
            if (!segments.isEmpty()) {
                var previous = segments.get(segments.size() - 1);
                if (!previous.destination().equals(origin)
                        || departure.isBefore(ItineraryRules.connectAfter(previous.plannedArrival(), allocations.get(0).operator()))) {
                    throw invalid("Invalid connection");
                }
            }
            segments.add(new Segment(leg.segmentIndex(), origin, destination, departure, arrival, allocations));
        }
        var assumptions = List.of("Connections: 90 min towards air, 30 min otherwise",
                "Car times are planned by the traveler; reservations use inclusive dates");
        return new Basket(new Itinerary(selection.travelerCount(), selection.participants(), segments, assumptions),
                selection.promoCode(), categories);
    }

    private List<Offer> search(Lookup lookup, String token) {
        try {
            return partners.search(lookup.operator(), lookup.request(), token);
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OFFER_LOOKUP_UNAVAILABLE");
        }
    }

    private static void verifyCar(SelectedAllocation request, Set<String> drivers, LocalDate date,
            OffsetDateTime arrival, Place destination) {
        if (request.driverAlias() == null || !request.participantAliases().contains(request.driverAlias()) || !drivers.add(request.driverAlias())
                || !date.equals(request.startDate()) || request.endDate() == null
                || ChronoUnit.DAYS.between(date, request.endDate()) > 30
                || request.endDate().isBefore(date)
                || arrival.atZoneSameInstant(destination.zoneId()).toLocalDate().isAfter(request.endDate())) {
            throw invalid("Invalid car dates or driver");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }

    private record Lookup(Offer.Operator operator, OfferSearch request) {}
}
