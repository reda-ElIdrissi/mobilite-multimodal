package com.transport.reservation;

import java.time.*;
import java.util.ArrayList;
import java.util.Collection;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
@EnableScheduling
public class ReservationApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReservationApplication.class,args);
    }

    @Bean
    @Profile("!demo")
    Clock businessClock() { return Clock.systemUTC(); }

    @Bean
    @Profile("demo")
    Clock demonstrationClock() {
        var zone = ZoneId.of("Africa/Casablanca");
        return Clock.fixed(ZonedDateTime.of(2026,11,15,6,0,0,0,zone).toInstant(),zone);
    }

    @Bean
    SecurityFilterChain reservationSecurity(HttpSecurity http) {
        var scopes = new JwtGrantedAuthoritiesConverter();
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var list = new ArrayList<GrantedAuthority>(scopes.convert(jwt));
            var realm = jwt.getClaimAsMap("realm_access");
            if (realm != null && realm.get("roles") instanceof Collection<?> roles && roles.contains("SUPPORT_ADMIN")) {
                list.add(new SimpleGrantedAuthority("ROLE_SUPPORT_ADMIN"));
            }
            return list;
        });
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(r -> r
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/admin/reservations/**").hasRole("SUPPORT_ADMIN")
                        .requestMatchers("/api/v1/reservations","/api/v1/reservations/**").authenticated()
                        .requestMatchers("/internal/v1/reservations/*/outcome").hasAuthority("SCOPE_reservations:outcome")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))).build();
    }
}
