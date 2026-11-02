package com.transport.autopartage.vehiculeRoute;

import com.transport.autopartage.vehiculeRoute.dto.LocationRequestDTO;
import com.transport.autopartage.vehiculeRoute.dto.LocationHistoryItem;
import com.transport.autopartage.vehiculeRoute.dto.LocationResponseDTO;
import com.transport.autopartage.vehiculeRoute.dto.Trajet;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RouteService {
    private final LocationRepository locationRepository;
    private final VilleVehiculeRepository villeVehiculeRepository;
    private final VehiculeRepository vehiculeRepository;
    private final VehiculeLocationRepository vehiculeLocationRepository;
    private final VilleRepository villeRepository;

    public Page<LocationHistoryItem> history(String subject, boolean owner, Pageable page) {
        var locations = owner ? locationRepository.findByOwnerId(subject, page)
                : locationRepository.findByRenterId(subject, page);
        return locations.map(location -> new LocationHistoryItem(location.getId(),
                location.getDepart().getNom(), location.getDestination().getNom(),
                location.getDateDebut(), location.getDateFin(), location.getStatus(),
                location.getRatingOwner(), location.getRatingRenter()));
    }

    Page<LocationResponseDTO> getLocationsByOwnerId(String owner_id, Pageable pageable) {
        return locationRepository.findByOwnerId(owner_id, pageable).map(this::toLocationResponse);
    }

    Page<LocationResponseDTO> getLocationsByRenterId(String renter_id, Pageable pageable) {
        return locationRepository.findByRenterId(renter_id, pageable).map(this::toLocationResponse);
    }

    public Page<Trajet> getRoutesFrom(String depart, LocalDate debut, String categorie) {
        return new PageImpl<>(villeVehiculeRepository.findRoutesFrom(depart, debut, categorie));
    }

    LocationResponseDTO getRenterLocation(String renter_id) {
        return getLocationsByRenterId(renter_id, PageRequest.of(0, 1)).stream().findFirst().orElseThrow();
    }

    LocationResponseDTO getOwnerLocation(String owner_id) {
        return getLocationsByOwnerId(owner_id, PageRequest.of(0, 1)).stream().findFirst().orElseThrow();
    }

    Page<LocationResponseDTO> getLocationsByVehiculeId(long vehicule_id, LocalDate date, Pageable pageable) {
        return vehiculeLocationRepository.findLocationsByVehiculeIdOnDate(vehicule_id, date, pageable)
                .map(this::toLocationResponse);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LocationResponseDTO attemptHold(
            long vehiculeId, LocalDate debut, LocalDate fin, LocationRequestDTO request) {
        if (debut == null || fin == null || debut.isAfter(fin)) {
            throw new IllegalArgumentException("Specify a valid hold date range.");
        }
        if (vehiculeId <= 0 || request == null || request.renter_id() == null || request.renter_id().isBlank() || request.renter_id().length() > 255 || request.destinationId() <= 0) {
            throw new IllegalArgumentException("Vehicle and destination IDs must be positive and renter subject must be valid.");
        }

        Vehicule vehicule = vehiculeRepository.findByIdForUpdate(vehiculeId)
                .orElseThrow(() -> new VehiculeException.NotFound(vehiculeId));
        if (!vehicule.isAvailable()) {
            throw new HoldConflictException("This vehicle is disabled.");
        }
        long destinationId = request.destinationId();
        Ville destination = villeRepository.findById(destinationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown destination: " + destinationId));
        if (!villeVehiculeRepository.existsByVehicule_IdAndVille_IdAndDebutLessThanEqualAndFinGreaterThanEqual(
                vehiculeId, destinationId, debut, fin)) {
            throw new IllegalArgumentException("The destination is not allowed for the full date range.");
        }

        if (vehiculeLocationRepository.hasBookingConflict(vehiculeId, destinationId, debut, fin, java.time.Instant.now())) {
            throw new HoldConflictException("This vehicle already has an incompatible booking.");
        }
        Location location = createPending(vehicule, destination, debut, fin, request.renter_id());
        return DtoBuilder.buildLocationResponseDto(location, List.of(VehiculeService.toResponse(vehicule)));
    }

    Location createPending(Vehicule vehicule, Ville destination, LocalDate debut, LocalDate fin, String renter) {
        Location location = locationRepository.save(Location.builder()
                .ownerId(vehicule.getOwnerId())
                .renterId(renter)
                .depart(vehicule.getVille())
                .destination(destination)
                .dateDebut(debut)
                .dateFin(fin)
                .status(Status.PENDING)
                .joinedAt(LocalDate.now())
                .build());
        vehiculeLocationRepository.save(VehiculeLocation.builder()
                .vehicule(vehicule)
                .location(location)
                .debut(debut)
                .fin(fin)
                .build());
        return location;
    }

    private LocationResponseDTO toLocationResponse(Location location) {
        var vehicules = vehiculeLocationRepository.findByLocationId(location.getId(), Pageable.unpaged())
                .map(VehiculeLocation::getVehicule)
                .map(VehiculeService::toResponse)
                .getContent();
        return DtoBuilder.buildLocationResponseDto(location, vehicules);
    }

    public static final class HoldConflictException extends RuntimeException {
        HoldConflictException(String message) {
            super(message);
        }
    }
}
