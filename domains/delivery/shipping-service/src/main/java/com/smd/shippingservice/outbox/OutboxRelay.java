package com.smd.shippingservice.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes rows of {@code outbox_event} to Kafka.
 *
 * <p><b>Why an outbox:</b> writing to the database and sending to Kafka are two separate systems
 * (the "dual write" problem). Commit first and then send, and a crash in between loses the event;
 * send first and then commit, and a rollback publishes an event for something that never happened.
 * Instead, business code writes the event into {@code outbox_event} in the same local transaction as
 * the business change, so both commit or neither does, and this relay publishes committed rows afterwards.
 *
 * <p><b>Delivery is at least once:</b> if the relay crashes after Kafka acknowledged a record but before
 * {@code published_at} was committed, the record is sent again. Consumers de-duplicate on {@code eventId}.
 *
 * <p><b>Ordering:</b> rows are read oldest first and the batch stops at the first failure, so a later event
 * is never published before an earlier one. {@code FOR UPDATE SKIP LOCKED} lets several instances run the
 * relay without sending the same row twice at the same time.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final OutboxRelayProperties properties;
    private final OutboxTracing tracing;
    private final Counter publishFailures;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate transaction,
                       OutboxRelayProperties properties, OutboxTracing tracing, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transaction = transaction;
        this.properties = properties;
        this.tracing = tracing;
        this.publishFailures = Counter.builder("outbox.publish.failures")
                .description("Outbox rows that could not be sent to Kafka").register(meters);
        Gauge.builder("outbox.pending", this, OutboxRelay::pendingCount)
                .description("Outbox rows not yet published").register(meters);
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval}")
    void scheduledPoll() {
        if (properties.enabled()) {
            poll();
        }
    }

    /** Publishes everything that is pending, batch by batch. */
    public void poll() {
        int published;
        do {
            published = publishBatch();
        } while (published == properties.batchSize());   // keep going while there's a backlog
    }

    /** One batch in one transaction: the row locks are held until the rows are marked published. */
    public int publishBatch() {
        Integer published = transaction.execute(status -> {
            List<OutboxRow> rows = jdbc.sql("""
                            SELECT id, topic, aggregate_id, event_type, payload::text AS payload, trace_parent
                              FROM outbox_event
                             WHERE published_at IS NULL
                             ORDER BY created_at
                             LIMIT :limit
                               FOR UPDATE SKIP LOCKED""")
                    .param("limit", properties.batchSize())
                    .query(OutboxRow.class)
                    .list();
            int sent = 0;
            for (OutboxRow row : rows) {
                try {
                    send(row);
                } catch (Exception e) {
                    // stop here so later events of the same order are not published before this one
                    publishFailures.increment();
                    jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1 WHERE id = :id").param("id", row.id()).update();
                    log.warn("Outbox: could not publish {} {} (will retry): {}", row.eventType(), row.id(), e.toString());
                    break;
                }
                jdbc.sql("UPDATE outbox_event SET published_at = now() WHERE id = :id").param("id", row.id()).update();
                sent++;
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    private void send(OutboxRow row) throws Exception {
        // key = orderId (or productId): all events of one aggregate go to the same partition, in order
        ProducerRecord<String, String> record =
                new ProducerRecord<>(row.topic(), row.aggregateId().toString(), row.payload());
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        // continue the trace of the request (or event) that wrote this row; the Kafka producer observation
        // becomes a child of this span and passes the trace on in the record headers
        Span span = tracing.startPublishSpan(row.traceParent(), row.eventType());
        try (Tracer.SpanInScope ignored = tracing.inScope(span)) {
            kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (span != null) {
                span.error(e);
            }
            throw e;
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }

    private double pendingCount() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL").query(Long.class).single();
    }

    record OutboxRow(UUID id, String topic, UUID aggregateId, String eventType, String payload, String traceParent) {
    }
}
