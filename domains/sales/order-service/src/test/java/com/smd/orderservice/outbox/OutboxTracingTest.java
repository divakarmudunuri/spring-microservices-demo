package com.smd.orderservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.smd.orderservice.OrderServiceIntegrationTest;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CLAUDE.md 6.10: the trace of the request that wrote an outbox row continues when the relay publishes it.
 * {@code @SpringBootTest} leaves out the tracing observation handlers unless asked: without them the Kafka producer
 * observation wouldn't write {@code traceparent} into the record headers.
 */
@AutoConfigureObservability
class OutboxTracingTest extends OrderServiceIntegrationTest {

    @Autowired
    OutboxWriter outbox;

    @Autowired
    OutboxRelay relay;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    Tracer tracer;

    @Test
    void theRequestsTraceIsStoredAndContinuedOnKafka() {
        UUID orderId = UUID.randomUUID();
        Span request = tracer.nextSpan().name("test request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            transaction.executeWithoutResult(s -> outbox.orderEvent("ORDER_INITIATED", orderId, CUSTOMER, "first"));
        } finally {
            request.end();
        }
        String traceId = request.context().traceId();
        assertThat(traceParents(orderId)).containsExactly("00-" + traceId + "-" + request.context().spanId() + "-01");

        // no span (like a @Scheduled simulator): the order's latest trace is reused, so the order stays one trace
        transaction.executeWithoutResult(s -> outbox.orderEvent("ORDER_REJECTED", orderId, CUSTOMER, "second"));
        assertThat(traceParents(orderId)).hasSize(2).containsOnly("00-" + traceId + "-" + request.context().spanId() + "-01");

        relay.poll();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(Topics.ORDER_EVENTS));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                assertThat(received).filteredOn(r -> r.key().equals(orderId.toString())).hasSize(2);
            });
            assertThat(received).filteredOn(r -> r.key().equals(orderId.toString()))
                    .extracting(r -> header(r, "traceparent").split("-"))
                    .allSatisfy(parts -> {
                        assertThat(parts[1]).as("same trace as the request").isEqualTo(traceId);
                        assertThat(parts[2]).as("a new span (the relay's producer span)").isNotEqualTo(request.context().spanId());
                    });
        }
    }

    private List<String> traceParents(UUID orderId) {
        return jdbc.sql("SELECT trace_parent FROM outbox_event WHERE aggregate_id = :id ORDER BY created_at")
                .param("id", orderId).query(String.class).list();
    }

    private static KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "tracing-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        assertThat(header).as("header %s among %s", name, record.headers()).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
