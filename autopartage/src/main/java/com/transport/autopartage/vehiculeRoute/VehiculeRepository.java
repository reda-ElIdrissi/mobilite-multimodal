package com.transport.autopartage.vehiculeRoute;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
interface VehiculeRepository extends JpaRepository<Vehicule, Long> {
    Page<Vehicule> findByVilleId(long villeId, Pageable pageable);
    Page<Vehicule> findByCategorieId(long categorieId, Pageable pageable);
    Page<Vehicule> findByVilleIdAndCategorieId(long villeId, long categorieId, Pageable pageable);

    @EntityGraph(attributePaths = {"ville", "categorie"})
    Page<Vehicule> findByOwnerIdOrderByIdAsc(String ownerId, Pageable pageable);

    @Query("""
        select v from Vehicule v join fetch v.categorie
        where v.ville.nom = :origin and v.isAvailable = true and v.passengerCapacity >= :capacity
          and (:category is null or v.categorie.nom = :category)
          and exists (select 1 from VilleVehicule p
              where p.vehicule = v and p.ville.id = :destination and p.debut <= :start and p.fin >= :end)
          and not exists (select 1 from VehiculeLocation a
              where a.vehicule = v and
        """ + VehiculeLocationRepository.CONFLICT + """
        )
        order by v.id
        """)
    List<Vehicule> searchOffers(String origin, long destination, LocalDate start, LocalDate end,
            int capacity, String category, Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vehicule v where v.id = :vehiculeId")
    Optional<Vehicule> findByIdForUpdate(@Param("vehiculeId") long vehiculeId);

    boolean existsByImmatriculation(String immatriculation);

    boolean existsByImmatriculationAndIdNot(String immatriculation, long vehiculeId);

    @Query("""
            select v from Vehicule v
            where v.ville.nom = :ville and v.categorie.nom = :categorie and v.isAvailable = true
            """)
    Page<Vehicule> findByVilleNomAndCategorieNomAndisAvailableTrue(
            @Param("ville") String ville, @Param("categorie") String categorie, Pageable pageable);

    @Query("select v from Vehicule v where v.ville.nom = :ville and v.isAvailable = true")
    Page<Vehicule> findByVilleNomAndisAvailableTrue(@Param("ville") String ville, Pageable pageable);
}
