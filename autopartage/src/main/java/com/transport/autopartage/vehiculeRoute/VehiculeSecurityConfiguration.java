package com.transport.autopartage.vehiculeRoute;

import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class VehiculeSecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http) {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/internal/v1/offers/search").hasAuthority("SCOPE_offers:read")
                        .requestMatchers("/api/v1/vehicules", "/api/v1/vehicules/**").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/locations").hasAuthority("SCOPE_locations:read")
                        .requestMatchers(com.transport.mobility.operations.OperationsConfiguration.WRITE_PATHS).hasAuthority("SCOPE_operators:write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(authorities())))
                .build();
    }

    private JwtAuthenticationConverter authorities() {
        var scopes = new JwtGrantedAuthoritiesConverter();
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var result = new ArrayList<GrantedAuthority>(scopes.convert(jwt));
            Map<String, Object> access = jwt.getClaimAsMap("realm_access");
            if (access != null && access.get("roles") instanceof Collection<?> roles && roles.contains("OWNER")) {
                result.add(new SimpleGrantedAuthority("ROLE_OWNER"));
            }
            return result;
        });
        return converter;
    }
}
