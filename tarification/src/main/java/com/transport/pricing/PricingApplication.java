package com.transport.pricing;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
@org.springframework.context.annotation.Import(com.transport.mobility.events.EventTopology.class)
@org.springframework.scheduling.annotation.EnableScheduling
public class PricingApplication {
    public static void main(String[] args) {
        SpringApplication.run(PricingApplication.class, args);
    }

    @Bean
    Clock pricingClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecurityFilterChain pricingSecurity(HttpSecurity http) {
        var scopes = new JwtGrantedAuthoritiesConverter();
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new ArrayList<GrantedAuthority>(scopes.convert(jwt));
            var realm = jwt.getClaimAsMap("realm_access");
            if (realm != null && realm.get("roles") instanceof Collection<?> roles && roles.contains("PRICING_ADMIN")) {
                authorities.add(new SimpleGrantedAuthority("ROLE_PRICING_ADMIN"));
            }
            return authorities;
        });
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/admin/regles/**", "/api/v1/admin/promotions/**").hasRole("PRICING_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/internal/v1/pricing/preview", "/internal/v1/quotes").hasAuthority("SCOPE_pricing:issue")
                        .requestMatchers(HttpMethod.GET, "/internal/v1/quotes/{id}").hasAnyAuthority("SCOPE_pricing:issue", "SCOPE_pricing:claim")
                        .requestMatchers(HttpMethod.POST, "/internal/v1/quotes/{id}/claim").hasAuthority("SCOPE_pricing:claim")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))).build();
    }
}
