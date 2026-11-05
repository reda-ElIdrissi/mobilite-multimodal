package com.transport.air;

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
public class AirBridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AirBridgeApplication.class, args);
    }

    @Bean
    AirClient client(@Value("${partner.endpoint}") String address) throws Exception {
        return new AirClient(address);
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
