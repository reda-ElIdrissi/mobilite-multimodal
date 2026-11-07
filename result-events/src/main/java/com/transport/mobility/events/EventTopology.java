package com.transport.mobility.events;

import java.util.ArrayList;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class EventTopology {
    public static final String EXCHANGE = "mobility.events";
    public static final String WORK = "saga.work";
    public static final String REQUESTED = "saga.requested.v1";
    public static final String PRICING = "pricing.reservation-results";
    public static final String NOTIFICATION = "notification.reservation-results";
    private static final String DEAD_LETTERS = "mobility.events.dlx";

    @Bean
    Declarables mobilityEventTopology() {
        var events = new TopicExchange(EXCHANGE, true, false);
        var deadLetters = new DirectExchange(DEAD_LETTERS, true, false);
        var objects = new ArrayList<Declarable>();
        objects.add(events);
        objects.add(deadLetters);
        for (String name : new String[]{WORK, PRICING, NOTIFICATION}) {
            String deadRouting = name + ".dlq";
            var queue = QueueBuilder.durable(name).deadLetterExchange(DEAD_LETTERS).deadLetterRoutingKey(deadRouting).build();
            var dlq = QueueBuilder.durable(deadRouting).build();
            objects.add(queue);
            objects.add(dlq);
            objects.add(BindingBuilder.bind(dlq).to(deadLetters).with(deadRouting));
            objects.add(BindingBuilder.bind(queue).to(events).with(name.equals(WORK) ? REQUESTED : "reservation.*.v1"));
        }
        var audit = QueueBuilder.durable("reservation.step-audit").maxLength(10000).ttl(86400000).build();
        objects.add(audit);
        objects.add(BindingBuilder.bind(audit).to(events).with("saga.step-changed.v1"));
        return new Declarables(objects);
    }
}
