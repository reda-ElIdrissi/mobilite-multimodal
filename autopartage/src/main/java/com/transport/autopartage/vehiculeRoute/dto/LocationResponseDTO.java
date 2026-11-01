package com.transport.autopartage.vehiculeRoute.dto;

import java.time.LocalDate;
import java.util.List;


public record LocationResponseDTO(
        long id,
        String owner_id,
        String renter_id,
        String depart,
        String destination,
        LocalDate debut,
        LocalDate fin,
        List<VehiculeResponseDTO> vehicules
) {}
