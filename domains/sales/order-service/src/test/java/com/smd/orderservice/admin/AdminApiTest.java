package com.smd.orderservice.admin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.smd.orderservice.OrderServiceIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** CLAUDE.md 6.11: admins read every order, payment and stock level, and restock; customers can't. */
class AdminApiTest extends OrderServiceIntegrationTest {

    static final UUID ADMIN_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void adminListsAllOrdersWithFilters() throws Exception {
        UUID confirmed = order("adm-1", EARBUDS);
        UUID rejected = order("adm-2", MONITOR);   // out of stock → REJECTED

        mvc.perform(get("/api/admin/orders").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(rejected.toString()));   // newest first
        mvc.perform(get("/api/admin/orders").param("status", "CONFIRMED").with(admin()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(confirmed.toString()));
        mvc.perform(get("/api/admin/orders").param("userId", UUID.randomUUID().toString()).with(admin()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/orders").param("from", "2099-01-01T00:00:00Z").with(admin()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/orders").param("size", "1").param("page", "1").with(admin()))
                .andExpect(jsonPath("$.content[0].id").value(confirmed.toString()))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void adminReadsAnyOrderAndItsDetailsWithTheAdminTokenRelayed() throws Exception {
        UUID orderId = order("adm-details", EARBUDS);
        DOWNSTREAM.stubFor(WireMock.get(urlPathEqualTo("/api/shipments/by-order/" + orderId)).willReturn(aResponse().withStatus(404)));
        DOWNSTREAM.stubFor(WireMock.get(urlPathEqualTo("/api/tracking/orders/" + orderId)).willReturn(aResponse().withStatus(404)));

        mvc.perform(get("/api/admin/orders/{id}", orderId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(EARBUDS.toString()));
        mvc.perform(get("/api/admin/orders/{id}/details", orderId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.fullName").value("Demo Customer"))
                .andExpect(jsonPath("$.degraded").value(false));
        DOWNSTREAM.verify(getRequestedFor(urlPathMatching("/api/users/.*"))
                .withHeader("Authorization", equalTo("Bearer token-for-admin")));
    }

    @Test
    void paymentsAreListedWithTotalsByStatus() throws Exception {
        order("pay-1", EARBUDS);    // 79.99
        order("pay-2", CHARGER);    // 39.99

        mvc.perform(get("/api/admin/payments").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments.totalElements").value(2))
                .andExpect(jsonPath("$.totalsByStatus[0].status").value("CAPTURED"))
                .andExpect(jsonPath("$.totalsByStatus[0].count").value(2))
                .andExpect(jsonPath("$.totalsByStatus[0].amount").value(119.98));
        mvc.perform(get("/api/admin/payments").param("status", "REFUNDED").with(admin()))
                .andExpect(jsonPath("$.payments.totalElements").value(0));
    }

    @Test
    void inventoryShowsExactStockWithNamesFromProductService() throws Exception {
        mvc.perform(get("/api/admin/inventory").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(12)))
                .andExpect(jsonPath("$.namesAvailable").value(true))
                .andExpect(jsonPath("$.items[2].productId").value(KEYBOARD.toString()))
                .andExpect(jsonPath("$.items[2].name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.items[2].quantityOnHand").value(1));
    }

    @Test
    void inventoryStillShowsTheNumbersWhenProductServiceIsDown() throws Exception {
        DOWNSTREAM.stubFor(WireMock.get(urlPathEqualTo("/api/products")).willReturn(aResponse().withStatus(503)));

        mvc.perform(get("/api/admin/inventory").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namesAvailable").value(false))
                .andExpect(jsonPath("$.items[2].name").value(nullValue()))
                .andExpect(jsonPath("$.items[2].quantityOnHand").value(1));
    }

    @Test
    void restockAddsStockRecordsWhoAndPublishesTheChange() throws Exception {
        mvc.perform(post("/api/admin/inventory/{id}/restock", MONITOR).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5,\"note\":\"supplier delivery\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(5));

        assertThat(stock(MONITOR)).isEqualTo(5);
        assertThat(jdbc.sql("SELECT reason || ' ' || delta || ' ' || performed_by || ' ' || note FROM stock_movements")
                .query(String.class).single()).isEqualTo("RESTOCK 5 " + ADMIN_ID + " supplier delivery");
        assertThat(jdbc.sql("SELECT payload->'payload'->>'quantityOnHand' FROM outbox_event WHERE topic = 'inventory-events'")
                .query(String.class).single()).isEqualTo("5");
    }

    @Test
    void restockValidation() throws Exception {
        mvc.perform(post("/api/admin/inventory/{id}/restock", UUID.randomUUID()).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":5}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/inventory/{id}/restock", MONITOR).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":0}")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/orders", "/api/admin/payments", "/api/admin/inventory",
            "/api/admin/orders/00000000-0000-4000-8000-000000000001"})
    void customersCannotUseAdminEndpoints(String path) throws Exception {
        mvc.perform(get(path).with(customer(CUSTOMER))).andExpect(status().isForbidden());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerListsOnlyTheirOwnOrders() throws Exception {
        order("mine-1", EARBUDS);
        order("mine-2", CHARGER);

        mvc.perform(get("/api/orders").with(customer(CUSTOMER)))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/orders").with(customer("00000000-0000-4000-8000-0000000000c2")))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    private UUID order(String key, UUID product) throws Exception {
        String body = mvc.perform(post("/api/orders").with(customer(CUSTOMER)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + product + "\",\"quantity\":1}]}"))
                .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(body);
        return UUID.fromString(json.has("id") ? json.get("id").asText() : json.get("orderId").asText());
    }
}
