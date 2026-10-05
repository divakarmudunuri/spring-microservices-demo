package com.smd.productservice.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.smd.productservice.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** CLAUDE.md 6.12: stock levels follow INVENTORY_CHANGED; duplicates and out-of-order events are ignored. */
class AvailabilityTest extends PostgresIntegrationTest {

    // products no other test asserts on
    static final String HAMMOCK = "20000000-0000-4000-8000-000000000012";      // seeded IN_STOCK
    static final String BOTTLE = "20000000-0000-4000-8000-000000000009";       // seeded IN_STOCK
    static final String DAYPACK = "20000000-0000-4000-8000-000000000010";      // seeded IN_STOCK

    static final KafkaProducer<String, String> PRODUCER = new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
            new StringSerializer(), new StringSerializer());

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @AfterAll
    static void close() {
        PRODUCER.close();
    }

    @Test
    void stockGoingToZeroShowsOutOfStockEvenThroughTheCache() throws Exception {
        // read first, so the old level is in the cache
        mvc.perform(get("/api/products/{id}", HAMMOCK)).andExpect(jsonPath("$.availability").value("IN_STOCK"));

        publish(UUID.randomUUID(), HAMMOCK, 0, Instant.now());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(get("/api/products/{id}", HAMMOCK)).andExpect(jsonPath("$.availability").value("OUT_OF_STOCK")));
    }

    @Test
    void lowAndInStockFollowTheThreshold() throws Exception {
        publish(UUID.randomUUID(), BOTTLE, 5, Instant.now());
        await().atMost(Duration.ofSeconds(20)).until(() -> level(BOTTLE).equals("LOW_STOCK"));
        publish(UUID.randomUUID(), BOTTLE, 6, Instant.now().plusSeconds(1));
        await().atMost(Duration.ofSeconds(20)).until(() -> level(BOTTLE).equals("IN_STOCK"));
    }

    @Test
    void duplicatesAndOlderEventsAreIgnored() {
        Instant now = Instant.now();
        UUID applied = UUID.randomUUID();
        publish(applied, DAYPACK, 0, now);
        await().atMost(Duration.ofSeconds(20)).until(() -> level(DAYPACK).equals("OUT_OF_STOCK"));

        publish(UUID.randomUUID(), DAYPACK, 50, now.minusSeconds(60));   // older: ignored
        publish(applied, DAYPACK, 50, now.plusSeconds(60));              // same event id again: ignored
        UUID marker = UUID.randomUUID();
        publish(marker, DAYPACK, 0, now.plusSeconds(1));                 // newer, same level: processed last
        await().atMost(Duration.ofSeconds(20)).until(() ->
                jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = ?").param(marker).query(Integer.class).single() == 1);

        assertThat(level(DAYPACK)).isEqualTo("OUT_OF_STOCK");
    }

    private String level(String productId) {
        return jdbc.sql("SELECT level FROM product_availability WHERE product_id = ?::uuid").param(productId)
                .query(String.class).single();
    }

    private static void publish(UUID eventId, String productId, int quantity, Instant occurredAt) {
        PRODUCER.send(new ProducerRecord<>("inventory-events", productId, """
                {"eventId":"%s","eventType":"INVENTORY_CHANGED","orderId":null,"userId":null,"occurredAt":"%s",
                 "source":"order-service","version":1,"payload":{"productId":"%s","quantityOnHand":%d}}"""
                .formatted(eventId, occurredAt, productId, quantity)));
        PRODUCER.flush();
    }
}
