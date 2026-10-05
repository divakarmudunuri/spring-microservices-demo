package com.smd.orderservice.compensation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.orderservice.OrderServiceIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** CLAUDE.md 6.6: FULFILLMENT_FAILED after payment → stock and money back, order CANCELLED, exactly once. */
class CompensationTest extends OrderServiceIntegrationTest {

    static final KafkaProducer<String, String> PRODUCER = new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
            new StringSerializer(), new StringSerializer());

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OrderCancellationService cancellation;

    @AfterAll
    static void close() {
        PRODUCER.close();
    }

    @Test
    void fulfillmentFailureRefundsAndRestocksEverything() throws Exception {
        UUID orderId = confirmedOrder("key-comp");   // 2 × earbuds + 1 × charger = 199.97
        assertThat(stock(EARBUDS)).isEqualTo(38);
        assertThat(balance(CUSTOMER)).isEqualByComparingTo("300.03");

        publish("FULFILLMENT_RECEIVED", orderId, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(20)).until(() -> "IN_FULFILLMENT".equals(statusOf(orderId)));
        publish("FULFILLMENT_FAILED", orderId, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(stock(EARBUDS)).isEqualTo(40);
            assertThat(stock(CHARGER)).isEqualTo(100);
            assertThat(balance(CUSTOMER)).isEqualByComparingTo("500.00");
        });
        assertThat(statusOf(orderId)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT status || ' ' || (refunded_at IS NOT NULL) FROM payments WHERE order_id = ?")
                .param(orderId).query(String.class).single()).isEqualTo("REFUNDED true");
        assertThat(jdbc.sql("SELECT type FROM wallet_transactions WHERE order_id = ? ORDER BY id").param(orderId)
                .query(String.class).list()).containsExactly("PAYMENT", "REFUND");
        assertThat(jdbc.sql("SELECT sum(delta) FROM stock_movements WHERE order_id = ?").param(orderId)
                .query(Integer.class).single()).as("sale −3, cancellation +3").isZero();
        assertThat(orderEvents(orderId)).containsExactly("ORDER_INITIATED", "INVENTORY_RESERVED", "PAYMENT_CAPTURED",
                "ORDER_CONFIRMED", "INVENTORY_RESTORED", "PAYMENT_REFUNDED", "ORDER_CANCELLED");
        assertThat(jdbc.sql("""
                        SELECT payload->'payload'->>'quantityOnHand' FROM outbox_event
                         WHERE topic = 'inventory-events' AND aggregate_id = ? ORDER BY created_at DESC LIMIT 1""")
                .param(EARBUDS).query(String.class).single()).as("product-service learns the stock is back").isEqualTo("40");
        assertThat(jdbc.sql("SELECT payload->'payload'->>'reason' FROM outbox_event WHERE event_type = 'ORDER_CANCELLED'")
                .query(String.class).single()).isEqualTo("Simulated warehouse failure");
    }

    @Test
    void aRepeatedFulfillmentFailureChangesNothing() throws Exception {
        UUID orderId = confirmedOrder("key-twice");
        UUID eventId = UUID.randomUUID();
        publish("FULFILLMENT_FAILED", orderId, eventId);
        publish("FULFILLMENT_FAILED", orderId, eventId);              // redelivery of the same event
        publish("FULFILLMENT_FAILED", orderId, UUID.randomUUID());    // a second, different failure event

        await().atMost(Duration.ofSeconds(20)).until(() ->
                jdbc.sql("SELECT count(*) FROM processed_event").query(Integer.class).single() == 2);
        assertThat(balance(CUSTOMER)).isEqualByComparingTo("500.00");                  // refunded once, not twice
        assertThat(stock(EARBUDS)).isEqualTo(40);
        assertThat(jdbc.sql("SELECT count(*) FROM wallet_transactions WHERE type = 'REFUND'").query(Integer.class).single()).isOne();
        assertThat(orderEvents(orderId)).filteredOn("ORDER_CANCELLED"::equals).hasSize(1);
    }

    @Test
    void cancellingTwiceDirectlyIsAlsoSafe() throws Exception {
        UUID orderId = confirmedOrder("key-direct");

        assertThat(cancellation.cancel(orderId, "test")).isTrue();
        assertThat(cancellation.cancel(orderId, "test")).isFalse();
        assertThat(balance(CUSTOMER)).isEqualByComparingTo("500.00");
    }

    @Test
    void aFailureForAnOrderThatAlreadyShippedGoesToTheDeadLetterTopic() throws Exception {
        UUID orderId = confirmedOrder("key-shipped");
        jdbc.sql("UPDATE orders SET status = 'SHIPPED' WHERE id = ?").param(orderId).update();

        publish("FULFILLMENT_FAILED", orderId, UUID.randomUUID());

        try (var dlt = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            dlt.subscribe(List.of("fulfillment-events.DLT"));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                dlt.poll(Duration.ofMillis(200)).forEach(received::add);
                assertThat(received).extracting(ConsumerRecord::key).contains(orderId.toString());
            });
        }
        assertThat(statusOf(orderId)).isEqualTo("SHIPPED");
        assertThat(balance(CUSTOMER)).isEqualByComparingTo("300.03");
    }

    private UUID confirmedOrder(String key) throws Exception {
        String body = mvc.perform(post("/api/orders").with(customer(CUSTOMER)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + EARBUDS + "\",\"quantity\":2},"
                                + "{\"productId\":\"" + CHARGER + "\",\"quantity\":1}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private static void publish(String type, UUID orderId, UUID eventId) {
        PRODUCER.send(new ProducerRecord<>("fulfillment-events", orderId.toString(), """
                {"eventId":"%s","eventType":"%s","orderId":"%s","userId":"%s","occurredAt":"2026-10-05T10:00:00Z",
                 "source":"fulfillment-service","version":1,
                 "payload":{"fulfillmentId":"%s","reason":"Simulated warehouse failure"}}"""
                .formatted(eventId, type, orderId, CUSTOMER, UUID.randomUUID())));
        PRODUCER.flush();
    }

    private String statusOf(UUID orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = ?").param(orderId).query(String.class).single();
    }
}
