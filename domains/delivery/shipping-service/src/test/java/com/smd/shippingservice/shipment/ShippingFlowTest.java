package com.smd.shippingservice.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.shippingservice.ShippingIntegrationTest;
import com.smd.shippingservice.events.EventEnvelope;
import com.smd.shippingservice.outbox.OutboxRelay;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ShippingFlowTest extends ShippingIntegrationTest {

    @Autowired
    ShipmentRepository shipments;

    @Autowired
    ShipmentSimulator simulator;

    @Autowired
    ShippingService shippingService;

    @Autowired
    OutboxRelay relay;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MockMvc mvc;

    @Test
    void fulfillmentPackedCreatesALabel() {
        UUID orderId = UUID.randomUUID();
        publish("fulfillment-events", orderId, fulfillmentPacked(UUID.randomUUID(), orderId));

        await().atMost(Duration.ofSeconds(20)).until(() -> shipments.findByOrderId(orderId).isPresent());
        Shipment s = shipments.findByOrderId(orderId).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.LABEL_CREATED);
        assertThat(s.getTrackingNumber()).matches("SMD[A-Z2-9]{11}");
        assertThat(s.getUserId()).isEqualTo(CUSTOMER);
        assertThat(s.getShippingAddress().city()).isEqualTo("Detroit");
        assertThat(s.getEstimatedDelivery()).isNotNull();
        assertThat(outboxTypes(orderId)).containsExactly("SHIPMENT_CREATED");
    }

    @Test
    void anEventDeliveredTwiceIsHandledOnce() {
        UUID orderId = UUID.randomUUID();
        String event = fulfillmentPacked(UUID.randomUUID(), orderId);
        publish("fulfillment-events", orderId, event);
        publish("fulfillment-events", orderId, event);
        publish("fulfillment-events", orderId, fulfillmentPacked(UUID.randomUUID(), orderId));   // new id, same order

        await().atMost(Duration.ofSeconds(20)).until(() ->
                jdbc.sql("SELECT count(*) FROM processed_event").query(Integer.class).single() == 2);
        assertThat(jdbc.sql("SELECT count(*) FROM shipments").query(Integer.class).single()).isOne();
        assertThat(outboxTypes(orderId)).containsExactly("SHIPMENT_CREATED");
    }

    @Test
    void otherFulfillmentEventsAreIgnored() {
        UUID ignored = UUID.randomUUID();
        publish("fulfillment-events", ignored, fulfillmentPacked(UUID.randomUUID(), ignored).replace("FULFILLMENT_PACKED", "FULFILLMENT_PICKING"));
        UUID packed = UUID.randomUUID();
        publish("fulfillment-events", ignored, fulfillmentPacked(UUID.randomUUID(), packed));

        await().atMost(Duration.ofSeconds(20)).until(() -> shipments.findByOrderId(packed).isPresent());
        assertThat(shipments.findByOrderId(ignored)).isEmpty();
    }

    @Test
    void simulatorDeliversStepByStep() throws Exception {
        UUID orderId = label();

        List<ShipmentStatus> seen = new ArrayList<>();
        while (simulator.advanceDue() > 0) {
            seen.add(shipments.findByOrderId(orderId).orElseThrow().getStatus());
        }

        assertThat(seen).containsExactly(ShipmentStatus.PICKED_UP, ShipmentStatus.IN_TRANSIT,
                ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERED);
        assertThat(shipments.findByOrderId(orderId).orElseThrow().getDeliveredAt()).isNotNull();
        assertThat(outboxTypes(orderId)).containsExactly("SHIPMENT_CREATED", "SHIPMENT_PICKED_UP",
                "SHIPMENT_IN_TRANSIT", "SHIPMENT_OUT_FOR_DELIVERY", "SHIPMENT_DELIVERED");
        JsonNode delivered = objectMapper.readTree(jdbc.sql(
                "SELECT payload::text FROM outbox_event WHERE event_type = 'SHIPMENT_DELIVERED'").query(String.class).single());
        assertThat(delivered.at("/payload/deliveredAt").asText()).isNotBlank();
        assertThat(delivered.get("userId").asText()).isEqualTo(CUSTOMER.toString());
    }

    @Test
    void shipmentByOrder() throws Exception {
        UUID orderId = label();

        mvc.perform(get("/api/shipments/by-order/{orderId}", orderId).with(caller(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LABEL_CREATED"))
                .andExpect(jsonPath("$.carrier").value("DEMO-EXPRESS"))
                .andExpect(jsonPath("$.trackingNumber").isNotEmpty())
                .andExpect(jsonPath("$.shippingAddress.postalCode").value("48226"));
        mvc.perform(get("/api/shipments/by-order/{orderId}", UUID.randomUUID()).with(caller(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/shipment-not-found"));
    }

    @Test
    void onlyTheOwnerOrAnAdminSeesAShipment() throws Exception {
        UUID orderId = label();   // owned by CUSTOMER

        mvc.perform(get("/api/shipments/by-order/{orderId}", orderId)
                        .with(caller(UUID.fromString("00000000-0000-4000-8000-0000000000c2"), "CUSTOMER")))
                .andExpect(status().isNotFound());   // someone else's: 404, not 403
        mvc.perform(get("/api/shipments/by-order/{orderId}", orderId)
                        .with(caller(UUID.fromString("00000000-0000-4000-8000-0000000000a1"), "ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/shipments/by-order/{orderId}", orderId)).andExpect(status().isUnauthorized());
    }

    @Test
    void adminsListAllShipmentsAndFindOneByTrackingNumber() throws Exception {
        UUID first = label();
        UUID second = label();
        simulator.advanceDue();   // both → PICKED_UP
        String tracking = shipments.findByOrderId(second).orElseThrow().getTrackingNumber();
        RequestPostProcessor admin = caller(UUID.fromString("00000000-0000-4000-8000-0000000000a1"), "ADMIN");

        mvc.perform(get("/api/admin/shipments").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].orderId").value(second.toString()))   // newest first
                .andExpect(jsonPath("$.content[1].orderId").value(first.toString()));
        mvc.perform(get("/api/admin/shipments").param("status", "DELIVERED").with(admin))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/shipments/{tn}", tracking).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(second.toString()))
                .andExpect(jsonPath("$.userId").value(CUSTOMER.toString()));
        mvc.perform(get("/api/admin/shipments/{tn}", "SMDNOPE").with(admin)).andExpect(status().isNotFound());

        mvc.perform(get("/api/admin/shipments").with(caller(CUSTOMER, "CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/shipments")).andExpect(status().isUnauthorized());
    }

    static RequestPostProcessor caller(UUID id, String role) {
        return jwt().jwt(j -> j.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void relayPublishesShippingEventsKeyedByOrderId() {
        UUID orderId = label();
        simulator.advanceDue();
        relay.poll();

        try (var consumer = consumer("shipping-events")) {
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                pollInto(consumer, received);
                assertThat(received).filteredOn(r -> r.key().equals(orderId.toString())).hasSize(2);
            });
            assertThat(received).filteredOn(r -> r.key().equals(orderId.toString()))
                    .extracting(r -> new String(r.headers().lastHeader("eventType").value()))
                    .containsExactly("SHIPMENT_CREATED", "SHIPMENT_PICKED_UP");
        }
    }

    @Test
    void packedWithoutAnAddressGoesToTheDeadLetterTopic() {
        UUID orderId = UUID.randomUUID();
        publish("fulfillment-events", orderId, """
                {"eventId":"%s","eventType":"FULFILLMENT_PACKED","orderId":"%s","userId":"%s",
                 "occurredAt":"2026-10-05T10:00:04Z","source":"fulfillment-service","version":1,"payload":{}}"""
                .formatted(UUID.randomUUID(), orderId, CUSTOMER));

        try (var dlt = consumer("fulfillment-events.DLT")) {
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                pollInto(dlt, received);
                assertThat(received).extracting(ConsumerRecord::key).contains(orderId.toString());
            });
        }
    }

    /** A LABEL_CREATED shipment for a new order, created through the real handler. */
    private UUID label() {
        UUID orderId = UUID.randomUUID();
        try {
            shippingService.onFulfillmentPacked(objectMapper.readValue(fulfillmentPacked(UUID.randomUUID(), orderId), EventEnvelope.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return orderId;
    }
}
