package com.smd.ordertrackingservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smd.ordertrackingservice.TrackingIntegrationTest;
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
import org.springframework.test.web.servlet.MockMvc;

/** End to end: raw JSON records on order-events → DynamoDB → the tracking API. */
class OrderEventsConsumerTest extends TrackingIntegrationTest {

    static final String USER = "00000000-0000-4000-8000-0000000000c1";
    static final KafkaProducer<String, String> PRODUCER = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
            new StringSerializer(), new StringSerializer());

    @Autowired
    MockMvc mvc;

    @AfterAll
    static void closeProducer() {
        PRODUCER.close();
    }

    @Test
    void confirmedOrderShowsTheFullTimeline() throws Exception {
        UUID orderId = UUID.randomUUID();
        publish(orderId, event(orderId, "ORDER_INITIATED", "2026-10-04T18:50:00.100Z", "{\"items\":[{},{}],\"cartId\":null}"));
        publish(orderId, event(orderId, "INVENTORY_RESERVED", "2026-10-04T18:50:00.200Z", "{\"items\":[{},{}]}"));
        publish(orderId, event(orderId, "PAYMENT_CAPTURED", "2026-10-04T18:50:00.300Z", "{\"amount\":199.97,\"currency\":\"USD\"}"));
        publish(orderId, event(orderId, "ORDER_CONFIRMED", "2026-10-04T18:50:00.400123Z", "{\"items\":[{},{}],\"totalAmount\":199.97,\"currency\":\"USD\"}"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mvc.perform(get("/api/tracking/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("ORDER_CONFIRMED"))
                .andExpect(jsonPath("$.timeline", hasSize(4))));

        mvc.perform(get("/api/tracking/orders/{id}", orderId))
                .andExpect(jsonPath("$.timeline[*].status").value(contains(
                        "ORDER_INITIATED", "INVENTORY_RESERVED", "PAYMENT_CAPTURED", "ORDER_CONFIRMED")))
                .andExpect(jsonPath("$.timeline[0].source").value("order-service"))
                .andExpect(jsonPath("$.timeline[0].details.itemCount").value("2"))
                .andExpect(jsonPath("$.timeline[3].occurredAt").value("2026-10-04T18:50:00.400Z"))
                .andExpect(jsonPath("$.timeline[3].details.totalAmount").value("199.97"));
        mvc.perform(get("/api/tracking/orders/{id}/latest", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("ORDER_CONFIRMED"));
    }

    @Test
    void rejectedOrderShowsUpToo() throws Exception {
        UUID orderId = UUID.randomUUID();
        publish(orderId, event(orderId, "ORDER_INITIATED", "2026-10-04T18:51:00.100Z", "{\"items\":[{}]}"));
        publish(orderId, event(orderId, "ORDER_REJECTED", "2026-10-04T18:51:00.200Z", "{\"reason\":\"OUT_OF_STOCK\",\"detail\":\"Not enough stock\"}"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mvc.perform(get("/api/tracking/orders/{id}", orderId))
                .andExpect(jsonPath("$.currentStatus").value("ORDER_REJECTED"))
                .andExpect(jsonPath("$.timeline[1].details.reason").value("OUT_OF_STOCK")));
    }

    @Test
    void anEventDeliveredTwiceIsHandledOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        String initiated = event(orderId, "ORDER_INITIATED", "2026-10-04T18:52:00.100Z", "{\"items\":[{}]}");
        publish(orderId, initiated);
        publish(orderId, initiated);
        publish(orderId, event(orderId, "ORDER_FAILED", "2026-10-04T18:52:00.200Z", "{\"reason\":\"DEPENDENCY_UNAVAILABLE\"}"));

        // the last event is processed after both copies (same key → same partition, in order)
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mvc.perform(get("/api/tracking/orders/{id}", orderId))
                .andExpect(jsonPath("$.currentStatus").value("ORDER_FAILED")));
        mvc.perform(get("/api/tracking/orders/{id}", orderId)).andExpect(jsonPath("$.timeline", hasSize(2)));
    }

    @Test
    void unknownEventTypesAreSkippedNotFailed() throws Exception {
        UUID orderId = UUID.randomUUID();
        publish(orderId, event(orderId, "SOMETHING_NEW", "2026-10-04T18:53:00.100Z", "{}"));
        publish(orderId, event(orderId, "ORDER_INITIATED", "2026-10-04T18:53:00.200Z", "{\"items\":[]}"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mvc.perform(get("/api/tracking/orders/{id}", orderId))
                .andExpect(jsonPath("$.timeline", hasSize(1)))
                .andExpect(jsonPath("$.timeline[0].status").value("ORDER_INITIATED")));
    }

    @Test
    void poisonMessagesLandOnTheDeadLetterTopic() {
        UUID notJson = UUID.randomUUID();
        UUID missingOrderId = UUID.randomUUID();
        publish(notJson, "this is not json {");
        publish(missingOrderId, """
                {"eventId":"%s","eventType":"ORDER_INITIATED","userId":"%s","occurredAt":"2026-10-04T18:54:00Z",
                 "source":"order-service","version":1,"payload":{}}""".formatted(UUID.randomUUID(), USER));

        try (KafkaConsumer<String, String> dlt = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            dlt.subscribe(List.of("order-events.DLT"));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                dlt.poll(Duration.ofMillis(200)).forEach(received::add);
                assertThat(received).extracting(ConsumerRecord::key).contains(notJson.toString(), missingOrderId.toString());
            });
            ConsumerRecord<String, String> garbage = received.stream()
                    .filter(r -> r.key().equals(notJson.toString())).findFirst().orElseThrow();
            assertThat(garbage.value()).isEqualTo("this is not json {");   // original bytes, unchanged
            assertThat(garbage.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        }
    }

    @Test
    void eventsFromAllThreeTopicsMakeOneTimelineEvenOutOfOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        publishTo("order-events", orderId, event(orderId, "ORDER_CONFIRMED", "2026-10-04T18:55:00.100Z", "{\"items\":[{}]}"));
        // shipping's event is processed before fulfillment's: different topics, no ordering between them
        publishTo("shipping-events", orderId, event(orderId, "SHIPMENT_IN_TRANSIT", "2026-10-04T18:55:30.000Z",
                "{\"trackingNumber\":\"SMDTEST\"}").replace("order-service", "shipping-service"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mvc.perform(get("/api/tracking/orders/{id}", orderId))
                .andExpect(jsonPath("$.currentStatus").value("SHIPMENT_IN_TRANSIT")));
        publishTo("fulfillment-events", orderId, event(orderId, "FULFILLMENT_PACKED", "2026-10-04T18:55:10.000Z", "{}")
                .replace("order-service", "fulfillment-service"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mvc.perform(get("/api/tracking/orders/{id}", orderId))
                .andExpect(jsonPath("$.timeline", hasSize(3))));
        mvc.perform(get("/api/tracking/orders/{id}", orderId))
                .andExpect(jsonPath("$.currentStatus").value("SHIPMENT_IN_TRANSIT"))          // not moved back
                .andExpect(jsonPath("$.timeline[*].status").value(contains(
                        "ORDER_CONFIRMED", "FULFILLMENT_PACKED", "SHIPMENT_IN_TRANSIT")))     // time order
                .andExpect(jsonPath("$.timeline[1].source").value("fulfillment-service"))
                .andExpect(jsonPath("$.timeline[2].details.trackingNumber").value("SMDTEST"));
    }

    @Test
    void unknownOrderIsNotFound() throws Exception {
        mvc.perform(get("/api/tracking/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/tracking-not-found"));
        mvc.perform(get("/api/tracking/orders/{id}/latest", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    private static void publish(UUID key, String json) {
        publishTo("order-events", key, json);
    }

    private static void publishTo(String topic, UUID key, String json) {
        PRODUCER.send(new ProducerRecord<>(topic, key.toString(), json));
        PRODUCER.flush();
    }

    private static String event(UUID orderId, String type, String occurredAt, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","orderId":"%s","userId":"%s","occurredAt":"%s",
                 "source":"order-service","version":1,"payload":%s}"""
                .formatted(UUID.nameUUIDFromBytes((orderId + type).getBytes()), type, orderId, USER, occurredAt, payload);
    }
}
