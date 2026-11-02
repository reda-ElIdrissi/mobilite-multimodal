package com.transport.autopartage.vehiculeRoute.dto;

import java.time.LocalDate;

public record VehiculeResponseDTO(
        long id,
        String ownerId,
        String marque,
        String modele,
        String immatriculation,
        String couleur,
        String ville,
        String categorie,
        LocalDate addedAt,
        boolean isAvailable,
        Long dailyRateMinor,
        Integer passengerCapacity,
        long priceVersion
) {}
