package com.transport.autopartage.vehiculeRoute.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record LocationRequestDTO(@NotBlank String renter_id, @Positive long destinationId) {}
