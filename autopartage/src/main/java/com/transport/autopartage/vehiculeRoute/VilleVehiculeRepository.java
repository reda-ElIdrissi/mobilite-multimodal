package com.transport.autopartage.vehiculeRoute;

import com.transport.autopartage.vehiculeRoute.dto.Trajet;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VilleVehiculeRepository extends JpaRepository<VilleVehicule, Long> {
    @Query("""
            select destination
            from VilleVehicule destination
            where destination.ville.id = :villeId
              and :date between destination.debut and destination.fin
            order by destination.id
            """)
    Page<VilleVehicule> findByVilleIdOnDate(
            @Param("villeId") long villeId, @Param("date") LocalDate date, Pageable pageable);

    @Query("""
            select count(p) > 0 from VilleVehicule p
            where p.vehicule.id = :vehicle and p.debut <= p.fin and p.fin >= :date
            """)
    boolean hasValidPermission(long vehicle, LocalDate date);

    void deleteByVehicule_Id(long vehiculeId);

    boolean existsByVehicule_IdAndVille_IdAndDebutLessThanEqualAndFinGreaterThanEqual(
            long vehiculeId, long villeId, LocalDate debut, LocalDate fin);

    @Query("""
            select distinct new com.transport.autopartage.vehiculeRoute.dto.Trajet(
                v.id, v.ville.nom, destination.ville.nom, v.categorie.nom, :debut)
            from VilleVehicule destination
            join destination.vehicule v
            where v.ville.nom = :depart
              and v.isAvailable = true
              and :debut between destination.debut and destination.fin
              and v.categorie.nom = coalesce(:categorie, v.categorie.nom)
            order by v.id, destination.ville.nom
            """)
    List<Trajet> findRoutesFrom(
            @Param("depart") String depart, @Param("debut") LocalDate debut,
            @Param("categorie") String categorie);
}
