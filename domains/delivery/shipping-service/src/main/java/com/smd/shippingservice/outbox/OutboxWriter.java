package com.smd.shippingservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.shippingservice.events.EventEnvelope;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes events to {@code outbox_event} in the caller's transaction; {@link OutboxRelay} publishes them.
 * Business code never calls Kafka directly.
 */
@Component
public class OutboxWriter {

    private static final int ENVELOPE_VERSION = 1;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final OutboxTracing tracing;
    private final String source;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper objectMapper, Clock clock, OutboxTracing tracing,
                        @Value("${spring.application.name}") String source) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tracing = tracing;
        this.source = source;
    }

    /** An event about one order: the Kafka key is the order id. */
    @Transactional(propagation = Propagation.MANDATORY)   // must join the caller's business transaction
    public void append(String topic, String eventType, UUID orderId, UUID userId, Object payload) {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), eventType, orderId, userId, clock.instant(),
                source, ENVELOPE_VERSION, objectMapper.valueToTree(payload));
        // created_at = clock_timestamp(): now() is the transaction start, so events of one transaction would tie
        jdbc.sql("""
                        INSERT INTO outbox_event (id, topic, aggregate_id, event_type, payload, trace_parent, created_at)
                        VALUES (:id, :topic, :aggregateId, :eventType, CAST(:payload AS jsonb), :traceParent,
                                clock_timestamp())""")
                .param("id", envelope.eventId())
                .param("topic", topic)
                .param("aggregateId", orderId)
                .param("eventType", eventType)
                .param("payload", toJson(envelope))
                .param("traceParent", tracing.traceParentFor(orderId))   // so the relay can continue this trace
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
