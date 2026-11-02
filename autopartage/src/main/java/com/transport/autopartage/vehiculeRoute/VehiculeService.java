package com.transport.autopartage.vehiculeRoute;

import com.transport.autopartage.vehiculeRoute.dto.AddVehiculeRequestDTO;
import com.transport.autopartage.vehiculeRoute.dto.EditVehiculeRequestDTO;
import com.transport.autopartage.vehiculeRoute.dto.DestinationPermission;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.transport.autopartage.vehiculeRoute.dto.VehiculeResponseDTO;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VehiculeService {
    private static final String REGISTRATION_CONSTRAINT = "uk_vehicules_immatriculation";

    private final VehiculeRepository vehiculeRepository;
    private final VilleRepository villeRepository;
    private final CategorieRepository categorieRepository;
    private final VilleVehiculeRepository permissions;
    private final VehiculeLocationRepository assignments;
    private final CarCalendar calendar;

    @Transactional
    public VehiculeResponseDTO addVehicule(String callerId, AddVehiculeRequestDTO requestDTO) {
        if (vehiculeRepository.existsByImmatriculation(requestDTO.immatriculation())) {
            throw new VehiculeException.DuplicateRegistration();
        }

        Vehicule vehicule = new Vehicule();
        vehicule.setOwnerId(callerId);
        vehicule.setMarque(requestDTO.marque());
        vehicule.setModele(requestDTO.modele());
        vehicule.setImmatriculation(requestDTO.immatriculation());
        vehicule.setCouleur(requestDTO.couleur());
        vehicule.setVille(findVille(requestDTO.ville()));
        vehicule.setCategorie(findCategorie(requestDTO.categorie()));
        vehicule.setAddedAt(LocalDate.now());
        vehicule.setDailyRateMinor(requestDTO.dailyRateMinor());
        vehicule.setPassengerCapacity(requestDTO.passengerCapacity());

        return saveResponse(vehicule);
    }

    @Transactional
    public VehiculeResponseDTO editVehicule(long vehiculeId, String callerId, EditVehiculeRequestDTO requestDTO) {
        Vehicule vehicule = findOwnedVehiculeForUpdate(vehiculeId, callerId);

        String immatriculation = requestDTO.immatriculation();
        if (immatriculation != null && !immatriculation.equals(vehicule.getImmatriculation())
                && vehiculeRepository.existsByImmatriculationAndIdNot(immatriculation, vehiculeId)) {
            throw new VehiculeException.DuplicateRegistration();
        }

        if (requestDTO.marque() != null) {
            vehicule.setMarque(requestDTO.marque());
        }
        if (requestDTO.modele() != null) {
            vehicule.setModele(requestDTO.modele());
        }
        if (requestDTO.couleur() != null) {
            vehicule.setCouleur(requestDTO.couleur());
        }
        if (requestDTO.ville() != null && !requestDTO.ville().equals(vehicule.getVille().getNom())) {
            requireNoUpcomingBooking(vehiculeId);
            if (assignments.hasUnreturnedVehicle(vehiculeId, java.time.Instant.now())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "HANDOVER_REQUIRED");
            }
            vehicule.setVille(findVille(requestDTO.ville()));
            vehicule.setAvailable(false);
        }
        if (requestDTO.passengerCapacity() != null
                && !requestDTO.passengerCapacity().equals(vehicule.getPassengerCapacity())) {
            requireNoUpcomingBooking(vehiculeId);
            vehicule.setPassengerCapacity(requestDTO.passengerCapacity());
        }
        if (requestDTO.dailyRateMinor() != null
                && !requestDTO.dailyRateMinor().equals(vehicule.getDailyRateMinor())) {
            vehicule.setDailyRateMinor(requestDTO.dailyRateMinor());
            vehicule.setPriceVersion(Math.incrementExact(vehicule.getPriceVersion()));
        }
        if (requestDTO.categorie() != null) {
            vehicule.setCategorie(findCategorie(requestDTO.categorie()));
        }

        if (immatriculation != null) {
            vehicule.setImmatriculation(immatriculation);
        }
        return saveResponse(vehicule);
    }

    @Transactional
    public VehiculeResponseDTO disableVehicule(long vehiculeId, String callerId) {
        Vehicule vehicule = findOwnedVehiculeForUpdate(vehiculeId, callerId);
        vehicule.setAvailable(false);
        return toResponse(vehicule);
    }

    @Transactional
    public VehiculeResponseDTO enableVehicule(long id, String subject) {
        Vehicule vehicle = findOwnedVehiculeForUpdate(id, subject);
        if (vehicle.getDailyRateMinor() == null || vehicle.getPassengerCapacity() == null
                || !permissions.hasValidPermission(id, today())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "VEHICULE_NOT_CONFIGURED");
        }
        vehicle.setAvailable(true);
        return toResponse(vehicle);
    }

    @Transactional
    public List<DestinationPermission> replaceDestinations(long id, String subject, List<DestinationPermission> requested) {
        Vehicule vehicle = findOwnedVehiculeForUpdate(id, subject);
        requireNoUpcomingBooking(id);
        var replacement = requested.stream().distinct().map(permission -> {
            if (permission.fin().isBefore(permission.debut()) || permission.fin().isBefore(today())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PERMISSION_DATES");
            }
            return VilleVehicule.builder().vehicule(vehicle).ville(findVille(permission.destination()))
                    .debut(permission.debut()).fin(permission.fin()).build();
        }).toList();
        permissions.deleteByVehicule_Id(id);
        permissions.saveAll(replacement);
        if (replacement.isEmpty()) {
            vehicle.setAvailable(false);
        }
        return requested.stream().distinct().toList();
    }

    private LocalDate today() {
        return calendar.today(ZoneId.of("Europe/Paris"));
    }

    private void requireNoUpcomingBooking(long vehicle) {
        if (assignments.hasFutureCommitment(vehicle, today(), java.time.Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "VEHICULE_HAS_BOOKINGS");
        }
    }

    @Transactional(readOnly = true)
    public Page<VehiculeResponseDTO> getVehicule(String ownerId) {
        return getVehicule(ownerId, PageRequest.of(0, 20));
    }

    @Transactional(readOnly = true)
    public Page<VehiculeResponseDTO> getVehicule(String ownerId, Pageable pageable) {
        return vehiculeRepository.findByOwnerIdOrderByIdAsc(ownerId, pageable)
                .map(VehiculeService::toResponse);
    }

    private Vehicule findOwnedVehiculeForUpdate(long vehiculeId, String callerId) {
        Vehicule vehicule = vehiculeRepository.findByIdForUpdate(vehiculeId)
                .orElseThrow(() -> new VehiculeException.NotFound(vehiculeId));
        if (!vehicule.getOwnerId().equals(callerId)) {
            throw new VehiculeException.OwnershipRequired();
        }
        return vehicule;
    }

    private Ville findVille(String nom) {
        return villeRepository.findByNom(nom).orElseThrow(() -> new VehiculeException.UnknownCity(nom));
    }

    private Categorie findCategorie(String nom) {
        return categorieRepository.findByNom(nom).orElseThrow(() -> new VehiculeException.UnknownCategory(nom));
    }

    private VehiculeResponseDTO saveResponse(Vehicule vehicule) {
        Vehicule saved;
        try {
            saved = vehiculeRepository.saveAndFlush(vehicule);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof PSQLException violation
                        && "23505".equals(violation.getSQLState())
                        && violation.getServerErrorMessage() != null
                        && REGISTRATION_CONSTRAINT.equals(violation.getServerErrorMessage().getConstraint())) {
                    throw new VehiculeException.DuplicateRegistration(exception);
                }
            }
            throw exception;
        }
        return toResponse(saved);
    }

    static VehiculeResponseDTO toResponse(Vehicule vehicule) {
        return new VehiculeResponseDTO(
                vehicule.getId(),
                vehicule.getOwnerId(),
                vehicule.getMarque(),
                vehicule.getModele(),
                vehicule.getImmatriculation(),
                vehicule.getCouleur(),
                vehicule.getVille().getNom(),
                vehicule.getCategorie().getNom(),
                vehicule.getAddedAt(),
                vehicule.isAvailable(), vehicule.getDailyRateMinor(),
                vehicule.getPassengerCapacity(), vehicule.getPriceVersion());
    }
}
