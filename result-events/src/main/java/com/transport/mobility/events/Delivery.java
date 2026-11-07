package com.transport.mobility.events;

import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataAccessException;

public final class Delivery {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(Delivery.class);

    private Delivery() {}

    // Each invocation opens a new database transaction. The listener acknowledges only after success.
    public static void process(String queue, String messageId, Runnable work) {
        for (int attempt = 1; ; attempt++) {
            try {
                work.run();
                return;
            } catch (RuntimeException failure) {
                if (!(failure instanceof DataAccessException) || attempt >= 3) {
                    LOG.warn("Dead-lettering message {} from {} after {} attempt(s): {}",
                            messageId, queue, attempt, failure.getClass().getSimpleName());
                    throw new AmqpRejectAndDontRequeueException("Delivery rejected for " + queue, failure);
                }
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new org.springframework.amqp.ImmediateRequeueAmqpException("Consumer was interrupted", interrupted);
                }
            }
        }
    }
}
