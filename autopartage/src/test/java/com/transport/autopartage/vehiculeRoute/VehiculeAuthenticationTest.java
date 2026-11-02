package com.transport.autopartage.vehiculeRoute;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({VehiculeController.class, CarReadController.class})
@Import({VehiculeSecurityConfiguration.class, VehiculeService.class})
class VehiculeAuthenticationTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private VehiculeRepository vehicles;
    @MockitoBean private VilleRepository cities;
    @MockitoBean private CategorieRepository categories;
    @MockitoBean private VilleVehiculeRepository permissions;
    @MockitoBean private VehiculeLocationRepository assignments;
    @MockitoBean private CarCalendar calendar;
    @MockitoBean private CarSearchService search;
    @MockitoBean private RouteService routes;

    @BeforeEach
    void claims() {
        when(decoder.decode("owner-b")).thenReturn(token("owner-b", "locations:read", "OWNER"));
        when(decoder.decode("customer")).thenReturn(token("customer", "locations:read", "CUSTOMER"));
        when(decoder.decode("search-bff")).thenReturn(token("search-bff", "offers:read"));
    }

    @Test
    void requiresOwnerRoleAndUsesJwtSubjectForOwnership() throws Exception {
        mvc.perform(get("/api/v1/vehicules/mine").header("X-User-Id", "owner-a"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/vehicules/mine").header(HttpHeaders.AUTHORIZATION, "Bearer customer"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(vehicles);

        var vehicle = new Vehicule();
        vehicle.setOwnerId("owner-a");
        when(vehicles.findByIdForUpdate(1)).thenReturn(Optional.of(vehicle));
        mvc.perform(patch("/api/v1/vehicules/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer owner-b")
                        .header("X-User-Id", "owner-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couleur\":\"Vert\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admitsSearchServiceScopeAndRejectsUserToken() throws Exception {
        String body = """
                {"origin":"PAR","destination":"VERSAILLES","date":"2026-11-15"}
                """;
        mvc.perform(post("/internal/v1/offers/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer owner-b")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(search);
        mvc.perform(post("/internal/v1/offers/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer search-bff")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private static Jwt token(String sub, String scope, String... roles) {
        return Jwt.withTokenValue(sub).header("alg", "RS256").subject(sub).claim("scope", scope)
                .claim("realm_access", Map.of("roles", List.of(roles))).build();
    }
}
