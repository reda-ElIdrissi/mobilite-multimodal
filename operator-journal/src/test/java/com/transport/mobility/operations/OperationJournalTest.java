package com.transport.mobility.operations;

import com.transport.mobility.contracts.v1.*;
import com.transport.mobility.contracts.v1.OperationStatus.State;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LOT0_POSTGRES_URL", matches = ".+")
class OperationJournalTest {
    @Test
    void serializesDuplicateHoldsAndPersistsUnknownAcrossNewJournalInstances() throws Exception {
        String url = System.getenv("LOT0_POSTGRES_URL");
        String user = System.getenv("LOT0_POSTGRES_USER");
        String password = System.getenv("LOT0_POSTGRES_PASSWORD");
        String schema = "operator_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password);
                var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                String scoped = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
                var source = new DriverManagerDataSource(scoped, user, password);
                Flyway.configure().dataSource(source).load().migrate();
                var db = new JdbcTemplate(source);
                var tx = new DataSourceTransactionManager(source);
                var json = JsonMapper.builder().build();
                var partner = new ControlledParticipant();
                var journal = new OperationJournal(db, tx, json, partner);
                var hold = command("known");
                var executor = Executors.newSingleThreadExecutor();
                try {
                    var first = executor.submit(() -> journal.hold("svc", hold));
                    assertTrue(partner.entered.await(5, TimeUnit.SECONDS));
                    assertEquals(State.IN_FLIGHT, journal.hold("svc", hold).state());
                    assertEquals(State.REJECTED, journal.hold("svc", new HoldCommand(UUID.randomUUID(), hold.offerId(),
                            1, null, hold.actorUserId(), hold.reservationLineId())).state());
                    // Simulate loss of a bridge process after marking the request in flight.
                    db.update("UPDATE operator_operation SET started_at=now()-interval '1 minute' WHERE operation_id=?",
                            hold.operationId());
                    assertEquals(State.UNKNOWN, journal.status("svc", hold.operationId()).state());
                    assertEquals(State.UNKNOWN, journal.hold("svc", hold).state());
                    partner.release.countDown();
                    assertEquals(State.SUCCEEDED, first.get(6, TimeUnit.SECONDS).state());
                    assertEquals(1, partner.holdCalls.get());
                } finally {
                    partner.release.countDown();
                    executor.shutdownNow();
                }
                var unknown = command("timeout");
                assertEquals(State.UNKNOWN, journal.hold("svc", unknown).state());
                var restarted = new OperationJournal(db, tx, json, partner);
                assertEquals(State.UNKNOWN, restarted.hold("svc", unknown).state());
                assertEquals(2, partner.holdCalls.get());

                var cancel = new CancelCommand(UUID.randomUUID(), "hold-known",
                        CancelCommand.ReferenceKind.HOLD, hold.reservationLineId());
                assertEquals(State.UNKNOWN, restarted.cancel("svc", cancel).state());
                assertEquals(State.SUCCEEDED, restarted.cancel("svc", cancel).state());
                assertEquals(State.SUCCEEDED, restarted.cancel("svc", cancel).state());
                assertEquals(2, partner.cancelCalls.get());
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static HoldCommand command(String offer) {
        return new HoldCommand(UUID.randomUUID(), offer, 1, null, "user", UUID.randomUUID());
    }

    private static final class ControlledParticipant implements Participant {
        final AtomicInteger holdCalls = new AtomicInteger();
        final AtomicInteger cancelCalls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public HoldReceipt hold(HoldCommand command, Instant now) {
            holdCalls.incrementAndGet();
            if ("timeout".equals(command.offerId())) {
                throw new IllegalStateException("Simulated loss after send");
            }
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release the first operation");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return Participant.remoteHold("hold-known", command, now);
        }

        @Override
        public ConfirmationReceipt confirm(String reference, ConfirmCommand command) {
            throw new AssertionError("Not exercised");
        }

        @Override
        public CancellationReceipt cancel(CancelCommand command) {
            if (cancelCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("Simulated cancellation timeout");
            }
            return Participant.cancelled(command);
        }
    }
}
