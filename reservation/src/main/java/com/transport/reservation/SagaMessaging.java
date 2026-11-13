package com.transport.reservation;

import com.transport.mobility.events.Delivery;
import com.transport.mobility.observability.Telemetry;
import java.time.Instant;
import com.transport.mobility.events.EventTopology;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(EventTopology.class)
class SagaMessaging {
    @Component
    static class Outbox {
        private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(Outbox.class);
        private final JdbcTemplate db;
        private final RabbitTemplate rabbit;
        private final AmqpAdmin broker;
        private boolean legacyBindingsRetired;

        Outbox(JdbcTemplate db,RabbitTemplate rabbit,AmqpAdmin broker) {
            this.db=db;
            this.rabbit=rabbit;
            this.broker=broker;
            this.rabbit.setMandatory(true);
        }

        @Scheduled(fixedDelayString="${mobility.reservation.outbox-ms:500}")
        void publish() {
            var rows=db.query("""
                    SELECT e.id,e.reservation_id,e.event_type,e.payload,r.traceparent,r.created_at
                    FROM outbox_event e JOIN reservation r ON r.id=e.reservation_id
                    WHERE e.published_at IS NULL ORDER BY e.created_at LIMIT 20
                    """, (rs,n)->new Event(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant()));
            for(var event:rows) {
                var span = Telemetry.resume("outbox.publish", event.traceparent(),
                                event.reservationCreated().plusSeconds(120).isBefore(Instant.now()))
                        .setAttribute("saga.id", event.reservation().toString())
                        .setAttribute("messaging.destination.name", EventTopology.EXCHANGE)
                        .setAttribute("mobility.event", event.type()).startSpan();
                try (var scope = span.makeCurrent()) {
                    retireLegacyBindings();
                    var properties = new MessageProperties();
                    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    properties.setMessageId(event.id().toString());
                    properties.setHeader("reservationId",event.reservation().toString());
                    String context = Telemetry.traceparent();
                    if (context != null) {
                        properties.setHeader("traceparent", context);
                    }
                    var confirmation=new CorrelationData(event.id().toString());
                    // A result queue retains events until the consumers of the next lot are deployed.
                    rabbit.send(EventTopology.EXCHANGE,event.type(),new Message(event.payload().getBytes(StandardCharsets.UTF_8),properties),confirmation);
                    if (confirmation.getFuture().get(2,TimeUnit.SECONDS).ack() && confirmation.getReturned()==null) {
                        db.update("UPDATE outbox_event SET published_at=now() WHERE id=?",event.id());
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception unavailable) {
                    LOG.warn("Outbox publishing deferred: {}",unavailable.getClass().getSimpleName());
                    span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
                    return;
                } finally {
                    span.end();
                }
            }
        }

        private void retireLegacyBindings() {
            if (legacyBindingsRetired) {
                return;
            }
            unbind("reservation.saga",EventTopology.REQUESTED);
            unbind("reservation.results","reservation.*.v1");
            unbind("reservation.results","saga.step-changed.v1");
            legacyBindingsRetired = true;
        }

        private void unbind(String queue,String key) {
            if (broker.getQueueProperties(queue) != null) {
                broker.removeBinding(new Binding(queue,Binding.DestinationType.QUEUE,EventTopology.EXCHANGE,key,null));
            }
        }

        private record Event(UUID id,UUID reservation,String type,String payload,String traceparent, Instant reservationCreated) {}
    }

    @Component
    static class WakeListener {
        private final ReservationStore store;
        WakeListener(ReservationStore store) { this.store=store; }

        @RabbitListener(queues=EventTopology.WORK)
        void onWake(Message message) {
            var attributes = message.getMessageProperties();
            Delivery.process(EventTopology.WORK,attributes.getMessageId(),() -> {
                String id = attributes.getHeader("reservationId");
                if (!EventTopology.REQUESTED.equals(attributes.getReceivedRoutingKey()) || id == null
                        || attributes.getMessageId() == null) {
                    throw new IllegalArgumentException("Invalid saga wake event");
                }
                store.wakeEvent(UUID.fromString(attributes.getMessageId()),UUID.fromString(id));
            });
        }
    }
}
