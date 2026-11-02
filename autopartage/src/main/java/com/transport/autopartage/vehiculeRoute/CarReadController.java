package com.transport.autopartage.vehiculeRoute;

import com.transport.autopartage.vehiculeRoute.dto.LocationHistoryItem;
import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.OfferSearch;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
class CarReadController {
    private final RouteService routes;
    private final CarSearchService search;

    @PostMapping("/internal/v1/offers/search")
    List<Offer> offers(@RequestBody OfferSearch request) {
        return search.search(request);
    }

    @GetMapping("/api/v1/locations")
    Page<LocationHistoryItem> history(@AuthenticationPrincipal Jwt jwt, Pageable pageable,
            @RequestParam(defaultValue = "renter") Role role) {
        return routes.history(VehiculeController.subject(jwt), role == Role.owner, pageable);
    }

    enum Role { owner, renter }
}
