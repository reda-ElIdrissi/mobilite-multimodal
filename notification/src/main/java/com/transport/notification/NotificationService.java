package com.transport.notification;

import com.transport.mobility.events.ReservationEvent;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationService {
    private final JdbcTemplate db;

    NotificationService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public void receive(ReservationEvent event) {
        if (db.update("INSERT INTO inbox_event(event_id) VALUES (?) ON CONFLICT DO NOTHING", event.eventId()) == 0) {
            return;
        }
        int advanced = db.update("""
                INSERT INTO reservation_notification_version(reservation_id,user_id,aggregate_version)
                VALUES (?,?,?)
                ON CONFLICT(reservation_id) DO UPDATE SET aggregate_version = excluded.aggregate_version
                WHERE reservation_notification_version.user_id = excluded.user_id
                  AND reservation_notification_version.aggregate_version < excluded.aggregate_version
                """, event.reservationId(), event.userId(), event.aggregateVersion());
        if (advanced == 0) {
            String owner = db.queryForObject("SELECT user_id FROM reservation_notification_version WHERE reservation_id=?",
                    String.class, event.reservationId());
            if (!event.userId().equals(owner)) {
                throw new IllegalArgumentException("Reservation notification owner mismatch");
            }
            return;
        }
        String message = switch (event.payload().status()) {
            case CONFIRMED -> "Votre réservation est confirmée.";
            case CANCELLED -> "Votre réservation est annulée.";
            case FAILED_COMPENSATED -> "La réservation a échoué et les ressources ont été libérées.";
            case NEEDS_RECONCILIATION -> "Une vérification de votre réservation est en cours.";
        };
        db.update("""
                INSERT INTO notification(id,user_id,reservation_id,event_id,aggregate_version,type,message,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), event.userId(), event.reservationId(), event.eventId(),
                event.aggregateVersion(), event.payload().status().name(), message, Timestamp.from(event.occurredAt()));
    }

    List<NotificationView> list(String subject, int page, int size, boolean unread) {
        return db.query("""
                SELECT n.*, n.aggregate_version=v.aggregate_version AS current
                FROM notification n JOIN reservation_notification_version v ON v.reservation_id=n.reservation_id
                WHERE n.user_id=? AND (NOT ? OR n.read_at IS NULL)
                ORDER BY n.created_at DESC,n.id DESC LIMIT ? OFFSET ?
                """, (row, index) -> {
                    Timestamp readAt = row.getTimestamp("read_at");
                    return new NotificationView(row.getObject("id", UUID.class), row.getObject("reservation_id", UUID.class),
                            row.getString("type"), row.getString("message"), readAt == null ? "UNREAD" : "READ",
                            row.getTimestamp("created_at").toInstant(), readAt == null ? null : readAt.toInstant(),
                            row.getBoolean("current"));
                }, subject, unread, size, (long) page * size);
    }

    boolean markRead(String subject, UUID id) {
        return db.update("UPDATE notification SET read_at=coalesce(read_at,now()) WHERE id=? AND user_id=?", id, subject) == 1;
    }

    record NotificationView(UUID id, UUID reservationId, String type, String message, String status,
                            Instant createdAt, Instant readAt, boolean current) {}
}
