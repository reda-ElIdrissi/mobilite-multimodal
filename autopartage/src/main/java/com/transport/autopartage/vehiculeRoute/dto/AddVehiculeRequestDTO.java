package com.transport.autopartage.vehiculeRoute.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;

public record AddVehiculeRequestDTO(
        @NotBlank String marque,
        @NotBlank String modele,
        @NotBlank String immatriculation,
        @NotBlank String couleur,
        @NotBlank String ville,
        @NotBlank String categorie,
        @Positive Long dailyRateMinor,
        @Positive Integer passengerCapacity
) {}
