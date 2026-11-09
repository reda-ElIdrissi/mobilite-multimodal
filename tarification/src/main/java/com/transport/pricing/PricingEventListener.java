package com.transport.pricing;

import com.transport.mobility.events.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class PricingEventListener {
    private final JsonMapper json;
    private final QuoteResults results;

    PricingEventListener(JsonMapper json, QuoteResults results) {
        this.json = json;
        this.results = results;
    }

    @RabbitListener(queues=EventTopology.PRICING)
    public void receive(Message message) {
        var attributes = message.getMessageProperties();
        Delivery.process(EventTopology.PRICING,attributes.getMessageId(),
                () -> results.receive(ReservationEvent.read(json,message.getBody(),attributes.getReceivedRoutingKey())));
    }
}
