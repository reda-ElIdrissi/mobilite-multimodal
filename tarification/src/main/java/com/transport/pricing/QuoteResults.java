package com.transport.pricing;

import com.transport.mobility.events.ReservationEvent;
import com.transport.mobility.events.ReservationEvent.QuoteOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class QuoteResults {
    private final JdbcTemplate db;

    QuoteResults(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public void receive(ReservationEvent event) {
        if (db.update("INSERT INTO inbox_event(event_id) VALUES (?) ON CONFLICT DO NOTHING",event.eventId()) == 0) {
            return;
        }
        apply(event.payload().devisId(), event.reservationId(), event.aggregateVersion(), event.payload().quoteOutcome());
    }

    @Transactional
    public void reconcile(ReservationEvent.Outcome outcome, Due expected) {
        if (!expected.quote().equals(outcome.devisId()) || !expected.reservation().equals(outcome.reservationId())
                || outcome.aggregateVersion() < 1 || outcome.quoteOutcome() == null
                || outcome.everConfirmed() != (outcome.quoteOutcome() == QuoteOutcome.CONSUMED)
                || outcome.quoteOutcome() == QuoteOutcome.VOID
                    && !List.of("CANCELLED","FAILED_COMPENSATED").contains(outcome.reservationStatus())) {
            throw new IllegalArgumentException("Inconsistent reservation reconciliation response");
        }
        if (outcome.quoteOutcome() != QuoteOutcome.UNCHANGED) {
            apply(outcome.devisId(), outcome.reservationId(), outcome.aggregateVersion(), outcome.quoteOutcome());
        }
    }

    List<Due> due() {
        return db.query("""
                WITH due AS (
                    SELECT id FROM devis WHERE status='CLAIMED' AND next_reconcile_at<=now()
                    ORDER BY next_reconcile_at,id LIMIT 20 FOR UPDATE SKIP LOCKED
                )
                UPDATE devis d SET next_reconcile_at=now()+interval '60 seconds'
                FROM due WHERE d.id=due.id RETURNING d.id,d.reservation_id
                """, (rs,n) -> new Due(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)));
    }

    private void apply(UUID quote, UUID reservation, long version, QuoteOutcome effect) {
        var row = db.query("SELECT reservation_id,status,reservation_event_version FROM devis WHERE id=? FOR UPDATE",
                (rs,n) -> new Row(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3)),quote)
                .stream().findFirst().orElse(null);
        if (row == null || !reservation.equals(row.reservation())) {
            if (effect != QuoteOutcome.UNCHANGED) {
                throw new IllegalArgumentException("Reservation does not own the claimed quote");
            }
            return;
        }
        boolean promote = effect == QuoteOutcome.CONSUMED && !row.status().equals("CONSUMED");
        if (!promote && version <= row.version()) {
            return;
        }
        String status = promote ? "CONSUMED"
                : effect == QuoteOutcome.VOID && "CLAIMED".equals(row.status()) ? "VOID" : row.status();
        db.update("""
                UPDATE devis SET status=?, reservation_event_version=greatest(reservation_event_version,?),
                    version=version+CASE WHEN status<>? THEN 1 ELSE 0 END WHERE id=?
                """,status,version,status,quote);
    }

    record Due(UUID quote, UUID reservation) {}
    private record Row(UUID reservation,String status,long version) {}
}
