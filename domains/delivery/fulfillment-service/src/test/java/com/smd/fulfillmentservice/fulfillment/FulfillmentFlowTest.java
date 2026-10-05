package com.smd.fulfillmentservice.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.fulfillmentservice.FulfillmentIntegrationTest;
import com.smd.fulfillmentservice.events.EventEnvelope;
import com.smd.fulfillmentservice.outbox.OutboxRelay;
import com.smd.fulfillmentservice.outbox.OutboxWriter;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class FulfillmentFlowTest extends FulfillmentIntegrationTest {

    @Autowired
    FulfillmentSimulator simulator;

    @Autowired
    OutboxRelay relay;

    @Autowired
    FulfillmentRepository fulfillments;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OutboxWriter outboxWriter;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    FulfillmentService fulfillmentService;

    @Test
    void orderConfirmedCreatesAReceivedFulfillment() {
        UUID orderId = UUID.randomUUID();
        publish("order-events", orderId, orderConfirmed(UUID.randomUUID(), orderId));

        await().atMost(Duration.ofSeconds(20)).until(() -> fulfillments.findByOrderId(orderId).isPresent());
        Fulfillment f = fulfillments.findByOrderId(orderId).orElseThrow();
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.RECEIVED);
        assertThat(f.getUserId()).isEqualTo(CUSTOMER);
        assertThat(f.getShippingAddress().city()).isEqualTo("Detroit");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment_items").query(Integer.class).single()).isEqualTo(2);
        assertThat(outboxTypes(orderId)).containsExactly("FULFILLMENT_RECEIVED");
    }

    @Test
    void anEventDeliveredTwiceIsHandledOnce() {
        UUID orderId = UUID.randomUUID();
        String event = orderConfirmed(UUID.randomUUID(), orderId);
        publish("order-events", orderId, event);
        publish("order-events", orderId, event);
        // a later event on the same partition proves both copies were consumed
        UUID marker = UUID.randomUUID();
        publish("order-events", orderId, orderConfirmed(UUID.randomUUID(), marker).replace(marker.toString(), orderId.toString()));

        await().atMost(Duration.ofSeconds(20)).until(() ->
                jdbc.sql("SELECT count(*) FROM processed_event").query(Integer.class).single() == 2);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillments").query(Integer.class).single()).isOne();
        assertThat(outboxTypes(orderId)).containsExactly("FULFILLMENT_RECEIVED");
    }

    @Test
    void otherOrderEventsAreIgnored() {
        UUID orderId = UUID.randomUUID();
        publish("order-events", orderId, orderConfirmed(UUID.randomUUID(), orderId).replace("ORDER_CONFIRMED", "ORDER_INITIATED"));
        UUID other = UUID.randomUUID();
        publish("order-events", orderId, orderConfirmed(UUID.randomUUID(), other));

        await().atMost(Duration.ofSeconds(20)).until(() -> fulfillments.findByOrderId(other).isPresent());
        assertThat(fulfillments.findByOrderId(orderId)).isEmpty();
    }

    @Test
    void simulatorPicksAndPacksAndPassesTheAddressOn() throws Exception {
        UUID orderId = receive();

        assertThat(simulator.advanceDue()).isOne();
        assertThat(fulfillments.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(FulfillmentStatus.PICKING);
        assertThat(simulator.advanceDue()).isOne();
        assertThat(fulfillments.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(FulfillmentStatus.PACKED);
        assertThat(simulator.advanceDue()).as("nothing left to do").isZero();

        assertThat(outboxTypes(orderId)).containsExactly("FULFILLMENT_RECEIVED", "FULFILLMENT_PICKING", "FULFILLMENT_PACKED");
        JsonNode packed = objectMapper.readTree(jdbc.sql(
                "SELECT payload::text FROM outbox_event WHERE event_type = 'FULFILLMENT_PACKED'").query(String.class).single());
        assertThat(packed.get("userId").asText()).isEqualTo(CUSTOMER.toString());
        assertThat(packed.at("/payload/shippingAddress/postalCode").asText()).isEqualTo("48226");
        assertThat(packed.at("/payload/warehouseCode").asText()).isNotBlank();
    }

    @Test
    void withAFailureRateOfOneEveryFulfillmentFails() {
        UUID orderId = receive();
        // a real simulator from the context's collaborators, with failure-rate 1.0
        var alwaysFails = new FulfillmentSimulator(jdbc, fulfillments, outboxWriter, transaction,
                new SimulationProperties(false, Duration.ZERO, 1.0, Duration.ofSeconds(1), "WH-TEST"), Clock.systemUTC());

        assertThat(alwaysFails.advanceDue()).isOne();

        Fulfillment f = fulfillments.findByOrderId(orderId).orElseThrow();
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(f.getFailureReason()).isNotBlank();
        assertThat(outboxTypes(orderId)).containsExactly("FULFILLMENT_RECEIVED", "FULFILLMENT_FAILED");
        assertThat(alwaysFails.advanceDue()).as("a failed fulfillment is final").isZero();
    }

    @Test
    void relayPublishesFulfillmentEventsKeyedByOrderId() {
        UUID orderId = receive();
        simulator.advanceDue();
        relay.poll();

        try (var consumer = consumer("fulfillment-events")) {
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                pollInto(consumer, received);
                assertThat(received).filteredOn(r -> r.key().equals(orderId.toString())).hasSize(2);
            });
            assertThat(received).filteredOn(r -> r.key().equals(orderId.toString()))
                    .extracting(r -> new String(r.headers().lastHeader("eventType").value()))
                    .containsExactly("FULFILLMENT_RECEIVED", "FULFILLMENT_PICKING");
        }
    }

    @Test
    void anUnusableOrderConfirmedGoesToTheDeadLetterTopic() {
        UUID orderId = UUID.randomUUID();
        publish("order-events", orderId, """
                {"eventId":"%s","eventType":"ORDER_CONFIRMED","orderId":"%s","userId":"%s",
                 "occurredAt":"2026-10-05T10:00:00Z","source":"order-service","version":1,"payload":{"items":[]}}"""
                .formatted(UUID.randomUUID(), orderId, CUSTOMER));

        try (var dlt = consumer("order-events.DLT")) {
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                pollInto(dlt, received);
                assertThat(received).extracting(ConsumerRecord::key).contains(orderId.toString());
            });
        }
        assertThat(fulfillments.findByOrderId(orderId)).isEmpty();
    }

    /** A RECEIVED fulfillment for a new order, created through the real handler. */
    private UUID receive() {
        UUID orderId = UUID.randomUUID();
        try {
            var event = objectMapper.readValue(orderConfirmed(UUID.randomUUID(), orderId), EventEnvelope.class);
            fulfillmentService.onOrderConfirmed(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return orderId;
    }
}
