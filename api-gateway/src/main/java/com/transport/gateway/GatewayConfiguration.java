package com.transport.gateway;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration(proxyBeanMethods = false)
class GatewayConfiguration {
    private static final String VEHICLES = "/api/v1/vehicules";
    private static final String VEHICLE_PATHS = VEHICLES + "/**";
    private static final String LOCATIONS = "/api/v1/locations";

    private static final String QUOTES = "/api/v1/quotes";
    private static final String QUOTE_PATHS = QUOTES + "/**";
    private static final String RULES = "/api/v1/admin/regles/**";
    private static final String PROMOTIONS = "/api/v1/admin/promotions/**";
    private static final String RESERVATIONS = "/api/v1/reservations";
    private static final String RESERVATION_PATHS = RESERVATIONS + "/**";
    private static final String RESERVATION_ADMIN = "/api/v1/admin/reservations/**";
    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final String NOTIFICATION_PATHS = NOTIFICATIONS + "/**";
    private static final String SEARCH = "/api/v1/search";

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http, @Value("${mobility.web-origin}") String origin) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "traceparent"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(VEHICLES, cors);
        source.registerCorsConfiguration(VEHICLE_PATHS, cors);
        source.registerCorsConfiguration(LOCATIONS, cors);
        for (String path : List.of(SEARCH, QUOTES, QUOTE_PATHS, RULES, PROMOTIONS, RESERVATIONS, RESERVATION_PATHS, RESERVATION_ADMIN, NOTIFICATIONS, NOTIFICATION_PATHS)) {
            source.registerCorsConfiguration(path, cors);
        }

        return http.cors(config -> config.configurationSource(source)).csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges.pathMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .pathMatchers(HttpMethod.POST, SEARCH).permitAll()
                        .pathMatchers(VEHICLES, VEHICLE_PATHS, LOCATIONS, QUOTES, QUOTE_PATHS, RULES, PROMOTIONS, RESERVATIONS, RESERVATION_PATHS, RESERVATION_ADMIN, NOTIFICATIONS, NOTIFICATION_PATHS)
                        .authenticated().anyExchange().denyAll())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    RouteLocator autopartage(RouteLocatorBuilder routes, @Value("${mobility.autopartage-url}") String uri,
            @Value("${mobility.bff-url}") String bff, @Value("${mobility.pricing-url}") String pricing,
            @Value("${mobility.reservation-url}") String reservation,
            @Value("${mobility.notification-url}") String notification) {
        return routes.routes().route("autopartage", route -> route.path(VEHICLES, VEHICLE_PATHS, LOCATIONS)
                .filters(filters -> filters.removeRequestHeader("X-User-Id")
                        .removeRequestHeader("X-Owner-Id").removeRequestHeader("X-Renter-Id")
                        .removeRequestHeader("X-Actor-User-Id"))
                .uri(uri)).route("search-bff", route -> route.path(SEARCH).and().method(HttpMethod.POST)
                        .filters(filters -> filters.removeRequestHeader("Authorization").removeRequestHeader("X-User-Id")
                                .removeRequestHeader("X-Owner-Id").removeRequestHeader("X-Renter-Id")
                                .removeRequestHeader("X-Actor-User-Id"))
                        .uri(bff))
                .route("quotes", route -> route.path(QUOTES, QUOTE_PATHS).uri(bff))
                .route("pricing-admin", route -> route.path(RULES, PROMOTIONS).uri(pricing))
                .route("reservation", route -> route.path(RESERVATIONS, RESERVATION_PATHS, RESERVATION_ADMIN).uri(reservation))
                .route("notification", route -> route.path(NOTIFICATIONS, NOTIFICATION_PATHS).uri(notification)).build();
    }
}
