package com.transport.rail;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.transport.mobility.operations.OperationsConfiguration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
@Import(OperationsConfiguration.class)
public class RailBridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(RailBridgeApplication.class, args);
    }

    @Bean
    RailClient client(@Value("${partner.ior}") String address) {
        return new RailClient(Path.of(address));
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/internal/v1/offers/search").hasAuthority("SCOPE_offers:read")
                        .requestMatchers(OperationsConfiguration.WRITE_PATHS).hasAuthority("SCOPE_operators:write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .build();
    }
}
