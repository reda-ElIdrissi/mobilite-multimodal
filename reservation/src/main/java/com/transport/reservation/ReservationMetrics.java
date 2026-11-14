package com.transport.reservation;

import com.transport.mobility.events.EventTopology;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import java.util.Objects;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.AmqpException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class ReservationMetrics {
    ReservationMetrics(JdbcTemplate db, AmqpAdmin broker) {
        var meter = GlobalOpenTelemetry.getMeter("mobility");
        meter.gaugeBuilder("mobility.reservations").ofLongs().buildWithCallback(measurement ->
                db.query("SELECT status,count(*) FROM reservation GROUP BY status", rs -> {
                    measurement.record(rs.getLong(2), Attributes.builder().put("state", rs.getString(1)).build());
                }));
        meter.gaugeBuilder("mobility.outbox.pending").ofLongs().buildWithCallback(measurement ->
                measurement.record(Objects.requireNonNull(db.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class))));
        meter.gaugeBuilder("mobility.saga.oldest").setUnit("s").buildWithCallback(measurement ->
                measurement.record(Objects.requireNonNull(db.queryForObject("""
                        SELECT coalesce(extract(epoch FROM now()-min(created_at)),0) FROM reservation
                        WHERE status IN ('PENDING_QUOTE','HOLDING','CONFIRMING','COMPENSATING','CANCELLING','NEEDS_RECONCILIATION')
                        """, Double.class))));
        meter.gaugeBuilder("mobility.queue.dead.letters").ofLongs().buildWithCallback(measurement -> {
            for (var base : new String[]{EventTopology.WORK, EventTopology.PRICING, EventTopology.NOTIFICATION}) {
                String queue = base + ".dlq";
                try {
                    var info = broker.getQueueInfo(queue);
                    if (info != null) {
                        measurement.record(info.getMessageCount(), Attributes.builder().put("queue", queue).build());
                    }
                } catch (AmqpException unavailable) {
                    // A broker outage must not be reported as an empty queue.
                }
            }
        });
    }
}
