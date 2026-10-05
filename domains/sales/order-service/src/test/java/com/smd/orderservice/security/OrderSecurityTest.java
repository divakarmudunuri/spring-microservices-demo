package com.smd.orderservice.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.orderservice.OrderServiceIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** CLAUDE.md 6.11 ownership and role tests for order-service's customer endpoints. */
class OrderSecurityTest extends OrderServiceIntegrationTest {

    static final UUID CUSTOMER_B = UUID.fromString("00000000-0000-4000-8000-0000000000c2");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void customerBCannotReadCustomerAsOrderOrDetails() throws Exception {
        String orderId = placeOrderAsCustomerA();

        mvc.perform(get("/api/orders/{id}", orderId).with(customer(CUSTOMER_B))).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/{id}/details", orderId).with(customer(CUSTOMER_B))).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/{id}", orderId).with(customer(CUSTOMER))).andExpect(status().isOk());
    }

    @Test
    void anAdminCannotPlaceOrders() throws Exception {
        mvc.perform(post("/api/orders").with(admin()).header("Idempotency-Key", "key-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + EARBUDS + "\",\"quantity\":1}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void withoutATokenEverythingIs401() throws Exception {
        mvc.perform(get("/api/orders/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private String placeOrderAsCustomerA() throws Exception {
        String body = mvc.perform(post("/api/orders").with(customer(CUSTOMER)).header("Idempotency-Key", "key-own-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + EARBUDS + "\",\"quantity\":1}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
