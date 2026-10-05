package com.smd.orderservice.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.orderservice.OrderServiceIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class AcknowledgeDeliveryTest extends OrderServiceIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void aDeliveredOrderBecomesCompletedOnce() throws Exception {
        UUID orderId = confirmedOrder();
        jdbc.sql("UPDATE orders SET status = 'DELIVERED' WHERE id = ?").param(orderId).update();

        mvc.perform(post("/api/orders/{id}/acknowledge-delivery", orderId).with(customer(CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.deliveryAcknowledgedAt").isNotEmpty());
        mvc.perform(post("/api/orders/{id}/acknowledge-delivery", orderId).with(customer(CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));   // the same result, nothing changed

        assertThat(orderEvents(orderId)).filteredOn("DELIVERY_ACKNOWLEDGED"::equals).hasSize(1);
        assertThat(orderEvents(orderId)).last().isEqualTo("DELIVERY_ACKNOWLEDGED");
    }

    @Test
    void anOrderNotYetDeliveredCannotBeAcknowledged() throws Exception {
        UUID orderId = confirmedOrder();

        mvc.perform(post("/api/orders/{id}/acknowledge-delivery", orderId).with(customer(CUSTOMER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/not-delivered"));
    }

    @Test
    void onlyTheOwnerCanAcknowledge() throws Exception {
        UUID orderId = confirmedOrder();
        jdbc.sql("UPDATE orders SET status = 'DELIVERED' WHERE id = ?").param(orderId).update();

        mvc.perform(post("/api/orders/{id}/acknowledge-delivery", orderId).with(customer("00000000-0000-4000-8000-0000000000c2")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/{id}/acknowledge-delivery", orderId).with(admin())).andExpect(status().isForbidden());
    }

    private UUID confirmedOrder() throws Exception {
        String body = mvc.perform(post("/api/orders").with(customer(CUSTOMER)).header("Idempotency-Key", "ack-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + EARBUDS + "\",\"quantity\":1}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }
}
