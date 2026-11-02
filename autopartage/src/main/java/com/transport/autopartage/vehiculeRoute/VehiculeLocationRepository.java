package com.transport.autopartage.vehiculeRoute;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VehiculeLocationRepository extends JpaRepository<VehiculeLocation, Long> {
    String ACTIVE = """
            (a.location.status = com.transport.autopartage.vehiculeRoute.Status.CONFIRMED
             or (a.location.status = com.transport.autopartage.vehiculeRoute.Status.PENDING
                 and (a.location.expiresAt is null or a.location.expiresAt > :now)))
            """;
    String UNRETURNED = """
            (a.location.depart.id <> a.location.destination.id and a.location.handoverAt is null)
            """;
    String CONFLICT = ACTIVE + " and " + """
            ((a.debut <= :end and a.fin >= :start)
             or (a.fin < :start and
            """ + UNRETURNED + """
             ) or (a.debut > :end and :destination <> a.vehicule.ville.id))
            """;

    @Query("select count(a) > 0 from VehiculeLocation a where a.vehicule.id = :vehicle and " + CONFLICT)
    boolean hasBookingConflict(long vehicle, long destination, LocalDate start, LocalDate end, Instant now);

    @Query("select count(a) > 0 from VehiculeLocation a where a.vehicule.id = :vehicle and " + UNRETURNED + " and " + ACTIVE)
    boolean hasUnreturnedVehicle(long vehicle, Instant now);

    @Query("select distinct a.vehicule.id from VehiculeLocation a where a.location.status = com.transport.autopartage.vehiculeRoute.Status.PENDING and a.location.expiresAt <= :now order by a.vehicule.id")
    List<Long> dueVehicleIds(Instant now, Pageable page);

    @Query("select a.location from VehiculeLocation a where a.vehicule.id = :vehicle and a.location.status = com.transport.autopartage.vehiculeRoute.Status.PENDING and a.location.expiresAt <= :now")
    List<Location> dueLocations(long vehicle, Instant now);

    @Query("select a.location from VehiculeLocation a where a.vehicule.id = :vehicle and a.location.id = :location")
    Optional<Location> findLinkedLocation(long vehicle, long location);

    @Query("select count(a) > 0 from VehiculeLocation a where a.vehicule.id=:vehicle and a.fin>=:date and " + ACTIVE)
    boolean hasFutureCommitment(long vehicle, LocalDate date, Instant now);

    Page<VehiculeLocation> findByVehiculeId(long vehicule_id, Pageable pageable);
    Page<VehiculeLocation> findByLocationId(long location_id, Pageable pageable);

    @Query("""
            select distinct assignment.location
            from VehiculeLocation assignment
            where assignment.vehicule.id = :vehiculeId
              and :date between assignment.debut and assignment.fin
            """)
    Page<Location> findLocationsByVehiculeIdOnDate(
            @Param("vehiculeId") long vehiculeId, @Param("date") LocalDate date, Pageable pageable);

    /**
     * Checks inclusive dates. Supply non-null dates with {@code debut <= fin}.
     * For a booking, call {@link VehiculeRepository#findByIdForUpdate(long)}
     * first and hold its row lock through the assignment write in the same
     * READ COMMITTED transaction.
     */
    default boolean existsOverlappingLocation(long vehiculeId, LocalDate debut, LocalDate fin) {
        return existsByVehicule_IdAndDebutLessThanEqualAndFinGreaterThanEqualAndLocation_StatusIn(
                vehiculeId, fin, debut, Set.of(Status.PENDING, Status.CONFIRMED));
    }

    boolean existsByVehicule_IdAndDebutLessThanEqualAndFinGreaterThanEqualAndLocation_StatusIn(
            long vehiculeId, LocalDate latestDebut, LocalDate earliestFin, Set<Status> statuses);
}
