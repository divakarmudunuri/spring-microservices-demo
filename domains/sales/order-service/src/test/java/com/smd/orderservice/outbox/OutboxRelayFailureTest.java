package com.smd.orderservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smd.orderservice.OrderServiceIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

/** A failed send stops the batch (ordering) and counts an attempt; nothing is marked published. */
class OutboxRelayFailureTest extends OrderServiceIntegrationTest {

    @Autowired
    OutboxWriter outbox;

    @Autowired
    TransactionTemplate transaction;

    @Test
    @SuppressWarnings("unchecked")
    void firstFailureStopsTheBatch() {
        UUID orderId = UUID.randomUUID();
        transaction.executeWithoutResult(s -> {
            outbox.orderEvent("ORDER_INITIATED", orderId, CUSTOMER, "first");
            outbox.orderEvent("ORDER_REJECTED", orderId, CUSTOMER, "second");
        });
        KafkaTemplate<String, String> brokenKafka = mock(KafkaTemplate.class);
        when(brokenKafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, String>>failedFuture(new RuntimeException("broker down")));
        var meters = new SimpleMeterRegistry();
        var relay = new OutboxRelay(jdbc, brokenKafka, transaction,
                new OutboxRelayProperties(false, Duration.ofSeconds(1), 100, Duration.ofSeconds(1)), meters);

        assertThat(relay.publishBatch()).isZero();

        List<String> rows = jdbc.sql("SELECT event_type || ':' || attempts || ':' || (published_at IS NULL) FROM outbox_event ORDER BY created_at")
                .query(String.class).list();
        assertThat(rows).containsExactly("ORDER_INITIATED:1:true", "ORDER_REJECTED:0:true");
        assertThat(meters.get("outbox.publish.failures").counter().count()).isEqualTo(1);
        assertThat(meters.get("outbox.pending").gauge().value()).isEqualTo(2);
    }
}
