package com.transport.autopartage.vehiculeRoute;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Component
class CarCalendar {
    private final Clock businessClock;

    CarCalendar(Clock businessClock) {
        this.businessClock = businessClock;
    }

    LocalDate today(ZoneId zone) {
        return LocalDate.now(businessClock.withZone(zone));
    }

    Instant observedAt() {
        return Instant.now();
    }

    @Configuration(proxyBeanMethods = false)
    static class Clocks {
        @Bean
        @Profile("!demo")
        Clock realBusinessClock() {
            return Clock.systemUTC();
        }

        @Bean
        @Profile("demo")
        Clock demoBusinessClock() {
            return Clock.fixed(ZonedDateTime.of(2026, 11, 15, 6, 0, 0, 0,
                    ZoneId.of("Africa/Casablanca")).toInstant(), ZoneId.of("Africa/Casablanca"));
        }
    }
}
