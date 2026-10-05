package com.smd.orderservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes events to the {@code outbox_event} table. Business code never calls Kafka directly: the row
 * commits (or rolls back) together with the business change, and the outbox relay (phase 6) publishes it.
 */
@Component
public class OutboxWriter {

    static final String SOURCE = "order-service";
    static final int ENVELOPE_VERSION = 1;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final OutboxTracing tracing;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper objectMapper, Clock clock, OutboxTracing tracing) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tracing = tracing;
    }

    /** An event on {@code order-events}: the Kafka key is the order id. */
    @Transactional(propagation = Propagation.MANDATORY)   // must join the caller's business transaction
    public void orderEvent(String eventType, UUID orderId, UUID userId, Object payload) {
        append(Topics.ORDER_EVENTS, orderId, new EventEnvelope(UUID.randomUUID(), eventType, orderId, userId,
                clock.instant(), SOURCE, ENVELOPE_VERSION, payload));
    }

    /** An event on {@code inventory-events}: the Kafka key is the product id; no order or user. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void inventoryEvent(String eventType, UUID productId, Object payload) {
        append(Topics.INVENTORY_EVENTS, productId, new EventEnvelope(UUID.randomUUID(), eventType, null, null,
                clock.instant(), SOURCE, ENVELOPE_VERSION, payload));
    }

    private void append(String topic, UUID aggregateId, EventEnvelope envelope) {
        // created_at = clock_timestamp(), not the column default now(): now() is the transaction's start time,
        // so every event of one transaction would get the same value and the relay (which publishes in
        // created_at order) couldn't keep them in the order they were written.
        jdbc.sql("""
                        INSERT INTO outbox_event (id, topic, aggregate_id, event_type, payload, trace_parent, created_at)
                        VALUES (:id, :topic, :aggregateId, :eventType, CAST(:payload AS jsonb), :traceParent,
                                clock_timestamp())""")
                .param("id", envelope.eventId())
                .param("topic", topic)
                .param("aggregateId", aggregateId)
                .param("eventType", envelope.eventType())
                .param("payload", toJson(envelope))
                .param("traceParent", tracing.traceParentFor(aggregateId))   // so the relay can continue this trace
                .update();
    }

    private String toJson(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + envelope.eventType(), e);
        }
    }
}
