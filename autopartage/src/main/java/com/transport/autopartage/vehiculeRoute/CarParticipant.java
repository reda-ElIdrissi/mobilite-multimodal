package com.transport.autopartage.vehiculeRoute;

import com.transport.mobility.contracts.v1.*;
import com.transport.mobility.operations.Participant;
import com.transport.mobility.search.PlaceDirectory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CarParticipant implements Participant {
    private final VehiculeRepository vehicles;
    private final VilleRepository cities;
    private final VilleVehiculeRepository permissions;
    private final VehiculeLocationRepository assignments;
    private final RouteService routes;
    private final CarCalendar calendar;
    private final Duration ttl;

    CarParticipant(VehiculeRepository vehicles, VilleRepository cities, VilleVehiculeRepository permissions,
            VehiculeLocationRepository assignments, RouteService routes, CarCalendar calendar,
            @Value("${mobility.car.hold-ttl:PT10M}") Duration ttl) {
        this.vehicles = vehicles;
        this.cities = cities;
        this.permissions = permissions;
        this.assignments = assignments;
        this.routes = routes;
        this.calendar = calendar;
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Invalid car hold duration");
        }
        this.ttl = ttl;
    }

    @Override
    public boolean remote() {
        return false;
    }

    @Override
    public HoldReceipt hold(HoldCommand command, Instant startedAt) {
        var details = command.car();
        var offer = parseOffer(command.offerId());
        if (details == null || command.seats() != null || !offer.start().equals(details.startDate())
                || !offer.end().equals(details.endDate()) || details.passengerCount() < 1
                || details.driverAlias() == null || !details.driverAlias().matches("[A-Za-z0-9_-]{1,32}")
                || details.expectedDailyRateMinor() <= 0 || details.expectedPriceVersion() < 0
                || offer.start().isAfter(offer.end()) || ChronoUnit.DAYS.between(offer.start(), offer.end()) > 30) {
            throw new Rejected("INVALID_CAR_OFFER");
        }
        Vehicule vehicle = lock(offer.vehicle());
        Ville destination = cities.findById(offer.destination()).orElseThrow(() -> new Rejected("INVALID_CAR_OFFER"));
        try {
            if (!PlaceDirectory.require(details.destination()).displayName().equals(destination.getNom())
                    || !PlaceDirectory.require(details.origin()).displayName().equals(vehicle.getVille().getNom())) {
                throw new Rejected("INVALID_CAR_OFFER");
            }
        } catch (IllegalArgumentException invalid) {
            throw new Rejected("INVALID_CAR_OFFER");
        }
        if (offer.start().isBefore(calendar.today(zoneOf(vehicle.getVille().getNom())))) {
            throw new Rejected("PAST_CAR_OFFER");
        }
        if (!vehicle.isAvailable() || vehicle.getPassengerCapacity() == null
                || vehicle.getPassengerCapacity() < details.passengerCount()) {
            throw new Rejected("CAR_UNAVAILABLE");
        }
        if (vehicle.getDailyRateMinor() == null || vehicle.getDailyRateMinor() != details.expectedDailyRateMinor()
                || vehicle.getPriceVersion() != details.expectedPriceVersion()) {
            throw new Rejected("PRICE_CHANGED");
        }
        Instant now = Instant.now();
        expireLocked(vehicle.getId(), now);
        if (assignments.hasBookingConflict(vehicle.getId(), destination.id(), offer.start(), offer.end(), now)
                || !permissions.existsByVehicule_IdAndVille_IdAndDebutLessThanEqualAndFinGreaterThanEqual(
                        vehicle.getId(), destination.id(), offer.start(), offer.end())) {
            throw new Rejected("CAR_UNAVAILABLE");
        }
        Instant expiry = now.plus(ttl).truncatedTo(ChronoUnit.MICROS);
        Location location = routes.createPending(vehicle, destination, offer.start(), offer.end(), command.actorUserId());
        location.setExpiresAt(expiry);
        location.setHoldOperationId(command.operationId());
        location.setReservationLineId(command.reservationLineId());
        location.setQuotedDailyRateMinor(details.expectedDailyRateMinor());
        location.setDriverAlias(details.driverAlias());
        return new HoldReceipt("car-hold-" + vehicle.getId() + "-" + location.getId(), expiry, command.operationId());
    }

    @Override
    public ConfirmationReceipt confirm(String reference, ConfirmCommand command) {
        var pair = lockReference(reference, "car-hold-", command.reservationLineId());
        var location = pair.location();
        expire(location, Instant.now());
        if (location.getStatus() == Status.EXPIRED) {
            throw new Rejected("HOLD_EXPIRED");
        }
        if (location.getStatus() == Status.PENDING) {
            location.setStatus(Status.CONFIRMED);
            location.setConfirmedAt(Instant.now());
        } else if (location.getStatus() != Status.CONFIRMED) {
            throw new Rejected("HOLD_NOT_CONFIRMABLE");
        }
        return new ConfirmationReceipt("car-booking-" + pair.vehicle().getId() + "-" + location.getId(), command.operationId());
    }

    @Override
    public CancellationReceipt cancel(CancelCommand command) {
        boolean hold = command.referenceKind() == CancelCommand.ReferenceKind.HOLD;
        var location = lockReference(command.reference(), hold ? "car-hold-" : "car-booking-", command.reservationLineId()).location();
        expire(location, Instant.now());
        if (location.getHandoverAt() != null) {
            throw new Rejected("CAR_ALREADY_HANDED_OVER");
        }
        if (hold && location.getStatus() == Status.CONFIRMED) {
            throw new Rejected("BOOKING_REFERENCE_REQUIRED");
        }
        if (location.getStatus() == Status.PENDING || location.getStatus() == Status.CONFIRMED) {
            location.setStatus(Status.CANCELLED);
            location.setCancelledAt(Instant.now());
        }
        return Participant.cancelled(command);
    }

    @Transactional
    public CarHandoverController.Handover handover(long vehicleId, String owner, long locationId, String city) {
        var vehicle = vehicles.findByIdForUpdate(vehicleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CAR_NOT_FOUND"));
        if (!owner.equals(vehicle.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "OWNERSHIP_REQUIRED");
        }
        var location = assignments.findLinkedLocation(vehicleId, locationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND"));
        if (city == null || !location.getDestination().getNom().equals(city)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HANDOVER_CITY_MISMATCH");
        }
        if (location.getHandoverAt() == null) {
            var originZone = PlaceDirectory.zoneForName(location.getDepart().getNom())
                    .orElse(ZoneId.of("Europe/Paris"));
            if (location.getStatus() != Status.CONFIRMED || location.getDepart().id() == location.getDestination().id()
                    || !calendar.today(originZone).isAfter(location.getDateFin())
                    || assignments.hasFutureCommitment(vehicleId, location.getDateFin().plusDays(1), Instant.now())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "HANDOVER_NOT_ALLOWED");
            }
            location.setHandoverAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
            location.setHandoverCity(location.getDestination());
            vehicle.setVille(location.getDestination());
            vehicle.setAvailable(false);
        }
        return new CarHandoverController.Handover(locationId, city, location.getHandoverAt());
    }

    @Transactional
    public void expireDue() {
        Instant now = Instant.now();
        for (long vehicle : assignments.dueVehicleIds(now, PageRequest.of(0, 100))) {
            lock(vehicle);
            expireLocked(vehicle, now);
        }
    }

    private void expireLocked(long vehicle, Instant now) {
        assignments.dueLocations(vehicle, now).forEach(location -> expire(location, now));
    }

    private static void expire(Location location, Instant now) {
        if (location.getStatus() == Status.PENDING && location.getExpiresAt() != null && !location.getExpiresAt().isAfter(now)) {
            location.setStatus(Status.EXPIRED);
        }
    }

    private Pair lockReference(String reference, String prefix, java.util.UUID line) {
        if (!reference.startsWith(prefix)) {
            throw new Rejected("INVALID_CAR_REFERENCE");
        }
        try {
            String[] ids = reference.substring(prefix.length()).split("-", -1);
            if (ids.length != 2) {
                throw new Rejected("INVALID_CAR_REFERENCE");
            }
            var vehicle = lock(Long.parseLong(ids[0]));
            var location = assignments.findLinkedLocation(vehicle.getId(), Long.parseLong(ids[1]))
                    .filter(value -> line.equals(value.getReservationLineId()))
                    .orElseThrow(() -> new Rejected("REFERENCE_NOT_OWNED"));
            return new Pair(vehicle, location);
        } catch (NumberFormatException invalid) {
            throw new Rejected("INVALID_CAR_REFERENCE");
        }
    }

    private Vehicule lock(long vehicle) {
        return vehicles.findByIdForUpdate(vehicle).orElseThrow(() -> new Rejected("CAR_UNAVAILABLE"));
    }

    private static ZoneId zoneOf(String city) {
        return PlaceDirectory.zoneForName(city).orElseThrow(() -> new Rejected("INVALID_CAR_ORIGIN"));
    }

    private static OfferKey parseOffer(String reference) {
        try {
            if (reference.length() > 160 || !reference.startsWith("car-v1.")) {
                throw new IllegalArgumentException();
            }
            var tuple = new String(Base64.getUrlDecoder().decode(reference.substring(7)), StandardCharsets.UTF_8).split(":", -1);
            if (tuple.length != 4) {
                throw new IllegalArgumentException();
            }
            return new OfferKey(Long.parseLong(tuple[0]), Long.parseLong(tuple[1]), LocalDate.parse(tuple[2]), LocalDate.parse(tuple[3]));
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new Rejected("INVALID_CAR_OFFER");
        }
    }

    private record OfferKey(long vehicle, long destination, LocalDate start, LocalDate end) {}
    private record Pair(Vehicule vehicle, Location location) {}
}
