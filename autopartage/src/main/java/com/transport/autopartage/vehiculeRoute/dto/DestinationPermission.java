package com.transport.autopartage.vehiculeRoute.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record DestinationPermission(@NotBlank String destination, @NotNull LocalDate debut, @NotNull LocalDate fin) {}
