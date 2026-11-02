package com.transport.autopartage.vehiculeRoute;

import com.transport.autopartage.vehiculeRoute.dto.LocationResponseDTO;
import com.transport.autopartage.vehiculeRoute.dto.VehiculeResponseDTO;
import java.util.List;

final class DtoBuilder {
    private DtoBuilder() {}

    static LocationResponseDTO buildLocationResponseDto(Location location, List<VehiculeResponseDTO> vehicules) {
        return new LocationResponseDTO(
                location.getId(),
                location.getOwnerId(),
                location.getRenterId(),
                location.getDepart().getNom(),
                location.getDestination().getNom(),
                location.getDateDebut(),
                location.getDateFin(),
                vehicules);
    }
}
