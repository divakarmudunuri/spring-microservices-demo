package com.smd.orderservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.orderservice.OrderServiceIntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** CLAUDE.md 6.4: an outbox row ends up as a record on the topic. */
class OutboxRelayTest extends OrderServiceIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OutboxRelay relay;

    @Test
    void checkoutEventsArePublishedInOrderKeyedByOrderId() throws Exception {
        String body = mvc.perform(post("/api/orders")
                        .header("X-Demo-User-Id", CUSTOMER).header("Idempotency-Key", "key-relay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + EARBUDS + "\",\"quantity\":2}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(body).get("id").asText();
        relay.poll();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL").query(Integer.class).single())
                .as("every row marked published").isZero();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(Topics.ORDER_EVENTS, Topics.INVENTORY_EVENTS));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                assertThat(received).filteredOn(r -> r.key().equals(orderId)).hasSize(4);
                // earlier tests changed the earbuds' stock too: look for this order's change (40 − 2)
                assertThat(received).filteredOn(r -> r.key().equals(EARBUDS.toString()))
                        .anyMatch(r -> quantityOnHand(r) == 38);
            });

            List<ConsumerRecord<String, String>> forOrder = received.stream().filter(r -> r.key().equals(orderId)).toList();
            assertThat(forOrder).extracting(r -> header(r, "eventType"))
                    .containsExactly("ORDER_INITIATED", "INVENTORY_RESERVED", "PAYMENT_CAPTURED", "ORDER_CONFIRMED");
            assertThat(forOrder).extracting(ConsumerRecord::partition).as("one order, one partition").containsOnly(forOrder.get(0).partition());

            JsonNode confirmed = objectMapper.readTree(forOrder.get(3).value());
            assertThat(confirmed.get("orderId").asText()).isEqualTo(orderId);
            assertThat(confirmed.get("eventId").asText()).isNotBlank();
            assertThat(confirmed.at("/payload/totalAmount").decimalValue()).isEqualByComparingTo("159.98");

            ConsumerRecord<String, String> inventory = received.stream()
                    .filter(r -> r.key().equals(EARBUDS.toString()) && quantityOnHand(r) == 38)
                    .findFirst().orElseThrow();
            assertThat(header(inventory, "eventType")).isEqualTo("INVENTORY_CHANGED");
            assertThat(objectMapper.readTree(inventory.value()).get("orderId").isNull()).isTrue();
        }
    }

    private static KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
    }

    // parse, don't string-match: Postgres jsonb normalizes spacing and key order of the stored envelope
    private int quantityOnHand(ConsumerRecord<String, String> record) {
        try {
            return objectMapper.readTree(record.value()).at("/payload/quantityOnHand").asInt();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
