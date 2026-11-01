package com.transport.autopartage.vehiculeRoute;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface VilleRepository extends JpaRepository<Ville, Long> {
    Optional<Ville> findByNom(String nom);
    boolean existsByNom(String nom);
}
