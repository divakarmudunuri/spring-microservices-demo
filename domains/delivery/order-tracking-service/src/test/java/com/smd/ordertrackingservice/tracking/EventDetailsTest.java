package com.smd.ordertrackingservice.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EventDetailsTest {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void picksTheUsefulFieldsPerEventType() throws Exception {
        assertThat(EventDetails.from("ORDER_CONFIRMED", json.readTree(
                "{\"items\":[{},{}],\"totalAmount\":199.97,\"currency\":\"USD\",\"shippingAddress\":{\"city\":\"Detroit\"}}")))
                .containsExactly(entry("itemCount", "2"), entry("totalAmount", "199.97"), entry("currency", "USD"));
        assertThat(EventDetails.from("ORDER_CANCELLED", json.readTree("{\"reason\":\"Simulated warehouse failure\"}")))
                .containsEntry("reason", "Simulated warehouse failure");
        assertThat(EventDetails.from("INVENTORY_RESTORED", json.readTree("{\"items\":[{},{},{}]}")))
                .containsEntry("itemCount", "3");
        assertThat(EventDetails.from("SHIPMENT_CREATED", json.readTree("{\"trackingNumber\":\"SMDX\",\"carrier\":\"DEMO\"}")))
                .containsEntry("trackingNumber", "SMDX").containsEntry("carrier", "DEMO");
    }

    @Test
    void neverCopiesTheAddressOrUnknownFields() throws Exception {
        assertThat(EventDetails.from("FULFILLMENT_PACKED", json.readTree(
                "{\"shippingAddress\":{\"line1\":\"100 Example Street\"},\"secret\":\"x\"}"))).isEmpty();
        assertThat(EventDetails.from("ORDER_INITIATED", null)).isEmpty();
    }

    private static java.util.Map.Entry<String, String> entry(String k, String v) {
        return java.util.Map.entry(k, v);
    }
}
