package com.transport.autopartage.vehiculeRoute;

import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.OfferSearch;
import com.transport.mobility.contracts.v1.Place;
import com.transport.mobility.search.PlaceDirectory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
class CarSearchService {
    private final VehiculeRepository vehicles;
    private final VilleRepository cities;
    private final CarCalendar calendar;

    @Transactional(readOnly = true)
    List<Offer> search(OfferSearch query) {
        Place origin = place(query.origin());
        Place destination = place(query.destination());
        LocalDate start = query.date();
        LocalDate end = query.dateFin() == null ? start : query.dateFin();
        int capacity = query.capacity() == null ? 1 : query.capacity();
        if (start == null || end.isBefore(start) || start.isBefore(calendar.today(origin.zoneId())) || capacity < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH");
        }

        var destinationCity = cities.findByNom(destination.displayName());
        if (destinationCity.isEmpty()) {
            return List.of();
        }
        long destinationId = destinationCity.get().id();
        var observedAt = calendar.observedAt();
        return vehicles.searchOffers(origin.displayName(), destinationId, start, end, capacity, query.category(),
                        observedAt, PageRequest.of(0, 100)).stream()
                .map(vehicle -> new Offer(Offer.Operator.CAR, Offer.Mode.VOITURE,
                        offerId(vehicle.getId(), destinationId, start, end), origin, destination,
                        null, null, start, end, vehicle.getDailyRateMinor(), Offer.UnitType.DAY,
                        vehicle.getPriceVersion(), vehicle.getPassengerCapacity(),
                        Offer.AvailabilityKind.VEHICLE_CAPACITY, vehicle.getCategorie().getNom(), observedAt))
                .toList();
    }

    private static Place place(String code) {
        try {
            return PlaceDirectory.require(code);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_CITY_CODE");
        }
    }

    private static String offerId(long vehicle, long destination, LocalDate start, LocalDate end) {
        String tuple = vehicle + ":" + destination + ":" + start + ":" + end;
        return "car-v1." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tuple.getBytes(StandardCharsets.UTF_8));
    }
}
