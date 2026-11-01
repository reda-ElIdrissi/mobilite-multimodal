package com.transport.autopartage.vehiculeRoute;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

interface LocationRepository extends JpaRepository<Location, Long> {
    long countByStatusAndHoldOperationIdIsNotNull(Status status);

    Page<Location> findByOwnerId(String ownerId, Pageable pageable);
    Page<Location> findByRenterId(String renterId, Pageable pageable);
}
