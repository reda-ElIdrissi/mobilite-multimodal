package com.transport.pricing;

import com.transport.mobility.contracts.v1.QuoteContracts.*;
import com.transport.mobility.events.ReservationEvent;
import com.transport.mobility.events.ReservationEvent.QuoteOutcome;
import com.transport.pricing.PricingRules.*;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LOT0_POSTGRES_URL", matches = ".+")
class PricingPostgresTest {
    @Test
    void persistsImmutableQuotesAndClaimsOneReservationUnderConcurrency() throws Exception {
        String url = System.getenv("LOT0_POSTGRES_URL");
        String user = System.getenv("LOT0_POSTGRES_USER");
        String password = System.getenv("LOT0_POSTGRES_PASSWORD");
        String schema = "pricing_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password);
                var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            String scoped = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
            try (var context = new SpringApplicationBuilder(PricingApplication.class, TestClock.class)
                    .profiles("demo").run("--server.port=0", "--spring.rabbitmq.listener.simple.auto-startup=false", "--spring.datasource.url=" + scoped,
                            "--spring.datasource.username=" + user, "--spring.datasource.password=" + password)) {
                var service = context.getBean(PricingService.class);
                var clock = context.getBean(MutableClock.class);
                var db = context.getBean(JdbcTemplate.class);
                var basket = PricingEngineTest.sample("DEMO");
                assertEquals(409500, service.preview(new PreviewBatch(List.of(basket, basket))).get(0).total().amountMinor());
                assertEquals(0, db.queryForObject("SELECT count(*) FROM devis", Integer.class));
                var quote = service.issue(new Issue("owner", basket));
                assertEquals(409500, quote.price().total().amountMinor());
                assertEquals(3, db.queryForObject("SELECT count(*) FROM ligne_devis", Integer.class));
                assertEquals(5, db.queryForObject("SELECT count(*) FROM devis_ajustement", Integer.class));
                assertEquals(404, assertThrows(ResponseStatusException.class,
                        () -> service.get(quote.id(), "stranger")).getStatusCode().value());
                context.getBean(QuoteStore.class).saveRule(UUID.randomUUID(),
                        new RuleInput(Type.FIXED_FEE_PER_BOOKING, null, null, 10000L, null, 1, true, null, null));
                assertEquals(419500, service.preview(new PreviewBatch(List.of(basket))).get(0).total().amountMinor());
                var expiring = service.issue(new Issue("owner", basket));
                UUID a = UUID.randomUUID();
                UUID b = UUID.randomUUID();
                var start = new CountDownLatch(1);
                var executor = Executors.newFixedThreadPool(2);
                try {
                    var first = executor.submit(() -> compete(service, quote.id(), a, start));
                    var second = executor.submit(() -> compete(service, quote.id(), b, start));
                    start.countDown();
                    UUID winner = first.get(10, TimeUnit.SECONDS);
                    UUID other = second.get(10, TimeUnit.SECONDS);
                    assertNotEquals(winner, other);
                    if (winner == null) {
                        winner = other;
                    }
                    clock.now = clock.now.plusSeconds(301);
                    assertEquals(409500, service.claim(quote.id(), new Claim("owner", winner)).price().total().amountMinor());
                    var projection = context.getBean(QuoteResults.class);
                    var cancelled = event(quote.id(),winner,9,ReservationEvent.Status.CANCELLED,QuoteOutcome.CONSUMED);
                    projection.receive(cancelled);
                    projection.receive(cancelled);
                    projection.receive(event(quote.id(),winner,8,ReservationEvent.Status.CONFIRMED,QuoteOutcome.CONSUMED));
                    projection.receive(event(quote.id(),winner,10,ReservationEvent.Status.FAILED_COMPENSATED,QuoteOutcome.VOID));
                    assertEquals(Status.CONSUMED, service.get(quote.id(),"owner").status());
                    assertEquals(3,db.queryForObject("SELECT count(*) FROM inbox_event",Integer.class));
                    final UUID mismatched = UUID.randomUUID();
                    assertThrows(IllegalArgumentException.class,()-> projection.receive(
                            event(quote.id(),mismatched,20,ReservationEvent.Status.CANCELLED,QuoteOutcome.VOID)));
                    assertEquals(3,db.queryForObject("SELECT count(*) FROM inbox_event",Integer.class));
                    assertEquals(Status.EXPIRED, service.get(expiring.id(), "owner").status());
                    assertEquals(410, assertThrows(ResponseStatusException.class,
                            () -> service.claim(expiring.id(), new Claim("owner", UUID.randomUUID()))).getStatusCode().value());
                } finally {
                    executor.shutdownNow();
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static ReservationEvent event(UUID quote,UUID reservation,long version,ReservationEvent.Status status,QuoteOutcome effect) {
        String route = switch (status) {
            case CONFIRMED -> "reservation.confirmed.v1";
            case CANCELLED -> "reservation.cancelled.v1";
            case FAILED_COMPENSATED -> "reservation.failed-compensated.v1";
            case NEEDS_RECONCILIATION -> "reservation.reconciliation-required.v1";
        };
        return new ReservationEvent(UUID.randomUUID(),route,1,Instant.now(),reservation,version,"owner",reservation,
                reservation,new ReservationEvent.Payload(quote,status,effect,effect==QuoteOutcome.CONSUMED,null));
    }

    private static UUID compete(PricingService service, UUID quote, UUID reservation, CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            return service.claim(quote, new Claim("owner", reservation)).reservationId();
        } catch (ResponseStatusException error) {
            assertEquals(409, error.getStatusCode().value());
            return null;
        }
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        volatile Instant now = PricingEngineTest.NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
