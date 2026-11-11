package com.transport.autopartage.vehiculeRoute;

import com.transport.autopartage.AutopartageApplication;
import com.transport.mobility.contracts.v1.*;
import com.transport.mobility.operations.Participant;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LOT0_POSTGRES_URL", matches = ".+")
class CarLifecycleTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 15);
    private static final String OWNER = "74fe3186-57c1-43a6-8f75-95cc328f77b3";

    @Test
    void enforcesOneWayBothWaysAndSupportsExpiryAndHandover() throws Exception {
        String url = System.getenv("LOT0_POSTGRES_URL");
        String user = System.getenv("LOT0_POSTGRES_USER");
        String password = System.getenv("LOT0_POSTGRES_PASSWORD");
        String schema = "car_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password);
                var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                String scoped = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
                try (var context = new SpringApplicationBuilder(AutopartageApplication.class, TestClock.class)
                        .profiles("demo").run("--server.port=0", "--spring.datasource.url=" + scoped,
                                "--spring.datasource.username=" + user, "--spring.datasource.password=" + password)) {
                    var car = context.getBean(CarParticipant.class);
                    var search = context.getBean(CarSearchService.class);
                    var db = context.getBean(JdbcTemplate.class);
                    var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                    long vehicle = db.queryForObject("SELECT id FROM vehicules WHERE immatriculation='DEMO-LOT1'", Long.class);
                    long paris = db.queryForObject("SELECT id FROM villes WHERE nom='Paris'", Long.class);
                    long versailles = db.queryForObject("SELECT id FROM villes WHERE nom='Versailles'", Long.class);
                    db.update("INSERT INTO ville_vehicule(ville_id,vehicule_id,debut,fin) VALUES (?,?,'2026-11-15','2026-11-25')",
                            paris, vehicle);
                    var later = body(vehicle, paris, "PAR", DATE.plusDays(2));
                    var future = tx.execute(status -> car.hold(later, Instant.now()));
                    assertEquals("CAR_UNAVAILABLE", rejected(tx, () -> car.hold(body(vehicle, versailles, "VERSAILLES", DATE), Instant.now())));
                    assertTrue(search.search(new OfferSearch("PAR", "VERSAILLES", DATE, DATE, 1, null)).isEmpty());
                    tx.execute(status -> car.cancel(new CancelCommand(UUID.randomUUID(), future.holdId(),
                            CancelCommand.ReferenceKind.HOLD, later.reservationLineId())));

                    var oneWay = body(vehicle, versailles, "VERSAILLES", DATE);
                    var held = tx.execute(status -> car.hold(oneWay, Instant.now()));
                    assertEquals("CAR_UNAVAILABLE", rejected(tx, () -> car.hold(body(vehicle, paris, "PAR", DATE.plusDays(2)), Instant.now())));
                    // Move only the technical deadline in this disposable test schema.
                    db.update("UPDATE locations SET expires_at=now()-interval '1 second' WHERE hold_operation_id=?", oneWay.operationId());
                    assertEquals("HOLD_EXPIRED", rejected(tx, () -> car.confirm(held.holdId(),
                            new ConfirmCommand(UUID.randomUUID(), oneWay.reservationLineId()))));
                    assertEquals("EXPIRED", db.queryForObject("SELECT status FROM locations WHERE hold_operation_id=?",
                            String.class, oneWay.operationId()));

                    var metrics = context.getBean(CarHoldMetrics.class);
                    assertEquals(1L, metrics.expiredHolds());
                    var unattended = body(vehicle, versailles, "VERSAILLES", DATE);
                    tx.execute(status -> car.hold(unattended, Instant.now()));
                    db.update("UPDATE locations SET expires_at=now()-interval '1 second' WHERE hold_operation_id=?",
                            unattended.operationId());
                    assertEquals(1L, metrics.expiredHolds());
                    car.expireDue();
                    assertEquals(2L, metrics.expiredHolds());
                    car.expireDue();
                    assertEquals(2L, metrics.expiredHolds());

                    var replacement = body(vehicle, versailles, "VERSAILLES", DATE);
                    var confirmed = tx.execute(status -> {
                        var receipt = car.hold(replacement, Instant.now());
                        return car.confirm(receipt.holdId(), new ConfirmCommand(UUID.randomUUID(), replacement.reservationLineId()));
                    });
                    long loc = db.queryForObject("SELECT id FROM locations WHERE hold_operation_id=?", Long.class, replacement.operationId());
                    assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                            () -> car.handover(vehicle, OWNER, loc, "Versailles")).getStatusCode().value());
                    context.getBean(MutableClock.class).now = Instant.parse("2026-11-16T10:00:00Z");
                    var handover = car.handover(vehicle, OWNER, loc, "Versailles");
                    assertEquals(handover, car.handover(vehicle, OWNER, loc, "Versailles"));
                    assertEquals(Boolean.FALSE, db.queryForObject("SELECT is_available FROM vehicules WHERE id=?", Boolean.class, vehicle));
                    assertEquals("CAR_ALREADY_HANDED_OVER", rejected(tx, () -> car.cancel(new CancelCommand(UUID.randomUUID(),
                            confirmed.bookingRef(), CancelCommand.ReferenceKind.BOOKING, replacement.reservationLineId()))));
                    context.getBean(VehiculeService.class).enableVehicule(vehicle, OWNER);
                    assertEquals(1, search.search(new OfferSearch("VERSAILLES", "PAR", DATE.plusDays(1), null, 1, null)).size());
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static String rejected(TransactionTemplate tx, Runnable action) {
        return tx.execute(status -> assertThrows(Participant.Rejected.class, action::run).getMessage());
    }

    private static HoldCommand body(long vehicle, long destination, String code, LocalDate date) {
        var tuple = (vehicle + ":" + destination + ":" + date + ":" + date).getBytes(StandardCharsets.UTF_8);
        return new HoldCommand(UUID.randomUUID(), "car-v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(tuple),
                null, new HoldCommand.CarDetails(date, date, "PAR", code, 2, "p1", 30000, 0),
                "renter", UUID.randomUUID());
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock mutableBusinessClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-15T06:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
