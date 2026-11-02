package com.transport.autopartage.vehiculeRoute.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record EditVehiculeRequestDTO(
        @Pattern(regexp = NON_BLANK, message = "must not be blank") String marque,
        @Pattern(regexp = NON_BLANK, message = "must not be blank") String modele,
        @Pattern(regexp = NON_BLANK, message = "must not be blank") String immatriculation,
        @Pattern(regexp = NON_BLANK, message = "must not be blank") String couleur,
        @Pattern(regexp = NON_BLANK, message = "must not be blank") String ville,
        @Pattern(regexp = NON_BLANK, message = "must not be blank") String categorie,
        @Positive Long dailyRateMinor,
        @Positive Integer passengerCapacity
) {
    private static final String NON_BLANK = "(?s).*[^\\p{javaWhitespace}].*";
}
