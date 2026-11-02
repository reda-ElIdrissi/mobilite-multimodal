package com.transport.autopartage.vehiculeRoute;

import com.transport.autopartage.vehiculeRoute.dto.AddVehiculeRequestDTO;
import com.transport.autopartage.vehiculeRoute.dto.DestinationPermission;
import com.transport.autopartage.vehiculeRoute.dto.EditVehiculeRequestDTO;
import com.transport.autopartage.vehiculeRoute.dto.VehiculeResponseDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicules")
@RequiredArgsConstructor
class VehiculeController {
    private final VehiculeService vehiculeService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    VehiculeResponseDTO addVehicule(@AuthenticationPrincipal Jwt jwt,
                                    @Valid @RequestBody AddVehiculeRequestDTO request) {
        return vehiculeService.addVehicule(subject(jwt), request);
    }

    @GetMapping("/mine")
    Page<VehiculeResponseDTO> mine(@AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        return vehiculeService.getVehicule(subject(jwt), pageable);
    }

    @PatchMapping("/{vehiculeId}")
    VehiculeResponseDTO editVehicule(@AuthenticationPrincipal Jwt jwt, @PathVariable long vehiculeId,
                                     @Valid @RequestBody EditVehiculeRequestDTO request) {
        return vehiculeService.editVehicule(vehiculeId, subject(jwt), request);
    }

    @PatchMapping("/{vehiculeId}/disable")
    VehiculeResponseDTO disableVehicule(@AuthenticationPrincipal Jwt jwt, @PathVariable long vehiculeId) {
        return vehiculeService.disableVehicule(vehiculeId, subject(jwt));
    }

    @PatchMapping("/{vehiculeId}/enable")
    VehiculeResponseDTO enableVehicule(@AuthenticationPrincipal Jwt jwt, @PathVariable long vehiculeId) {
        return vehiculeService.enableVehicule(vehiculeId, subject(jwt));
    }

    @PutMapping("/{vehiculeId}/destinations")
    List<DestinationPermission> destinations(@AuthenticationPrincipal Jwt jwt, @PathVariable long vehiculeId,
            @Valid @RequestBody PermissionUpdate request) {
        return vehiculeService.replaceDestinations(vehiculeId, subject(jwt), request.permissions());
    }

    record PermissionUpdate(@NotNull @Size(max = 100)
                            List<@NotNull @Valid DestinationPermission> permissions) {}

    static String subject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new VehiculeException.InvalidCaller();
        }
        return subject;
    }
}
