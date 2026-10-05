package com.smd.orderservice.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.orderservice.OrderServiceIntegrationTest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** order-service follows fulfillment and shipping: forward only, each event once, ORDER_DELIVERED at the end. */
class DeliveryEventsTest extends OrderServiceIntegrationTest {

    static final KafkaProducer<String, String> PRODUCER = new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
            new StringSerializer(), new StringSerializer());

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @AfterAll
    static void close() {
        PRODUCER.close();
    }

    @Test
    void orderFollowsFulfillmentAndShippingToDelivered() throws Exception {
        UUID orderId = confirmedOrder("key-delivery");

        publish("fulfillment-events", orderId, "FULFILLMENT_RECEIVED", UUID.randomUUID());
        awaitStatus(orderId, "IN_FULFILLMENT");

        publish("shipping-events", orderId, "SHIPMENT_PICKED_UP", UUID.randomUUID());
        awaitStatus(orderId, "SHIPPED");

        UUID delivered = UUID.randomUUID();
        publish("shipping-events", orderId, "SHIPMENT_DELIVERED", delivered);
        publish("shipping-events", orderId, "SHIPMENT_DELIVERED", delivered);   // redelivery
        awaitStatus(orderId, "DELIVERED");

        await().atMost(Duration.ofSeconds(10)).until(() -> processed() == 3);
        assertThat(orderEvents(orderId)).containsExactly(
                "ORDER_INITIATED", "INVENTORY_RESERVED", "PAYMENT_CAPTURED", "ORDER_CONFIRMED", "ORDER_DELIVERED");
    }

    @Test
    void aLateEventNeverMovesTheStatusBack() throws Exception {
        UUID orderId = confirmedOrder("key-late");

        publish("shipping-events", orderId, "SHIPMENT_PICKED_UP", UUID.randomUUID());
        awaitStatus(orderId, "SHIPPED");
        publish("fulfillment-events", orderId, "FULFILLMENT_RECEIVED", UUID.randomUUID());   // arrives late

        await().atMost(Duration.ofSeconds(10)).until(() -> processed() == 2);
        assertThat(statusOf(orderId)).isEqualTo("SHIPPED");
    }

    @Test
    void eventsForARejectedOrderAreIgnored() throws Exception {
        String body = mvc.perform(post("/api/orders").with(customer(CUSTOMER)).header("Idempotency-Key", "key-rej")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + MONITOR + "\",\"quantity\":1}]}"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("orderId").asText());

        publish("fulfillment-events", orderId, "FULFILLMENT_RECEIVED", UUID.randomUUID());

        await().atMost(Duration.ofSeconds(10)).until(() -> processed() == 1);
        assertThat(statusOf(orderId)).isEqualTo("REJECTED");
    }

    private UUID confirmedOrder(String key) throws Exception {
        String body = mvc.perform(post("/api/orders").with(customer(CUSTOMER)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + EARBUDS + "\",\"quantity\":1}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private static void publish(String topic, UUID orderId, String type, UUID eventId) {
        PRODUCER.send(new ProducerRecord<>(topic, orderId.toString(), """
                {"eventId":"%s","eventType":"%s","orderId":"%s","userId":"%s","occurredAt":"2026-10-05T10:00:00Z",
                 "source":"test","version":1,"payload":{}}""".formatted(eventId, type, orderId, CUSTOMER)));
        PRODUCER.flush();
    }

    private void awaitStatus(UUID orderId, String expected) {
        await().atMost(Duration.ofSeconds(20)).until(() -> expected.equals(statusOf(orderId)));
    }

    private String statusOf(UUID orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = ?").param(orderId).query(String.class).single();
    }

    private int processed() {
        return jdbc.sql("SELECT count(*) FROM processed_event").query(Integer.class).single();
    }
}
