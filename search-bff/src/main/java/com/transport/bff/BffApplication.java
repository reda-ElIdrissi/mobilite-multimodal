package com.transport.bff;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
public class BffApplication {
    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }

    @Bean
    SecurityFilterChain bffSecurity(HttpSecurity http) {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/search").permitAll()
                        .requestMatchers("/api/v1/quotes", "/api/v1/quotes/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults())).build();
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService offerWorkers() {
        return new ThreadPoolExecutor(9, 9, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(24));
    }

    @Bean
    @Profile("demo")
    Clock demoSearchClock() {
        return Clock.fixed(ZonedDateTime.of(2026, 11, 15, 6, 0, 0, 0,
                    ZoneId.of("Africa/Casablanca")).toInstant(), ZoneId.of("Africa/Casablanca"));
    }

    @Bean
    @Profile("!demo")
    Clock liveSearchClock() {
        return Clock.systemUTC();
    }
}
