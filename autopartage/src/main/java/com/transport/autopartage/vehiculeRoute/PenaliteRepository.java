package com.transport.autopartage.vehiculeRoute;

import org.springframework.data.jpa.repository.JpaRepository;

interface PenaliteRepository extends JpaRepository<Penalite, Long> {
    Penalite findByType(String type);

}
