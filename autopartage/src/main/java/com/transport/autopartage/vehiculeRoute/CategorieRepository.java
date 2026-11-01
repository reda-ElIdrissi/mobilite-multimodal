package com.transport.autopartage.vehiculeRoute;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface CategorieRepository extends JpaRepository<Categorie, Long> {
    Optional<Categorie> findByNom(String nom);
}
