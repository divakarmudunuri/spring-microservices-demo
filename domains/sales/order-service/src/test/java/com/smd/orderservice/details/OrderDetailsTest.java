package com.smd.orderservice.details;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.smd.orderservice.client.FeignClientTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * CLAUDE.md 6.2: GET /api/orders/{id}/details fans out to four services in parallel and degrades instead of
 * failing. Runs with the short client timeouts of {@link FeignClientTest} (read timeout 300 ms).
 */
class OrderDetailsTest extends FeignClientTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    UUID orderId;

    @BeforeEach
    void placeOrderAndStubAllFour() throws Exception {
        stubUser(CUSTOMER, "ACTIVE", true);
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/products")).willReturn(okJson(PRODUCTS_JSON)));
        String body = mvc.perform(post("/api/orders").header("X-Demo-User-Id", CUSTOMER).header("Idempotency-Key", "key-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + EARBUDS + "\",\"quantity\":2},"
                                + "{\"productId\":\"" + CHARGER + "\",\"quantity\":1}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        orderId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/shipments/by-order/" + orderId)).willReturn(shipment()));
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/tracking/orders/" + orderId)).willReturn(tracking()));
    }

    @Test
    void everythingAvailable() throws Exception {
        details()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(199.97))
                .andExpect(jsonPath("$.items[*].name").value(containsInAnyOrder("Wireless Earbuds", "USB-C Charger 65W")))
                .andExpect(jsonPath("$.customer.fullName").value("Demo Customer"))
                .andExpect(jsonPath("$.customer.email").value("customer@demo.local"))
                .andExpect(jsonPath("$.shipment.trackingNumber").value("SMDABC"))
                .andExpect(jsonPath("$.tracking.currentStatus").value("SHIPMENT_IN_TRANSIT"))
                .andExpect(jsonPath("$.tracking.timeline[*].status").value(contains("ORDER_CONFIRMED", "SHIPMENT_IN_TRANSIT")))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.unavailableSections", empty()));
    }

    @Test
    void aFailingServiceDegradesOnlyItsSection() throws Exception {
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/shipments/by-order/" + orderId)).willReturn(aResponse().withStatus(500)));

        details()
                .andExpect(status().isOk())                                        // never a 500 for a partial failure
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.unavailableSections", contains("shipping")))
                .andExpect(jsonPath("$.shipment").value(nullValue()))
                .andExpect(jsonPath("$.customer.fullName").value("Demo Customer"))
                .andExpect(jsonPath("$.tracking.currentStatus").value("SHIPMENT_IN_TRANSIT"));
    }

    @Test
    void aSlowServiceTimesOutAndIsReportedUnavailable() throws Exception {
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/tracking/orders/" + orderId)).willReturn(tracking().withFixedDelay(1_000)));

        details()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailableSections", contains("tracking")))
                .andExpect(jsonPath("$.shipment.trackingNumber").value("SMDABC"));
    }

    @Test
    void productServiceDownStillShowsTheItemsWithoutNames() throws Exception {
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/products")).willReturn(aResponse().withStatus(503)));

        details()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailableSections", contains("products")))
                .andExpect(jsonPath("$.items[0].name").value(nullValue()))
                .andExpect(jsonPath("$.items[0].unitPrice").value(79.99));            // prices come from the order itself
    }

    @Test
    void everythingDownStillReturnsTheOrder() throws Exception {
        DOWNSTREAM.resetAll();
        DOWNSTREAM.stubFor(get(urlPathMatching("/api/.*")).willReturn(aResponse().withStatus(503)));

        details()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.unavailableSections", containsInAnyOrder("customer", "products", "shipping", "tracking")));
    }

    @Test
    void noShipmentOrTrackingYetIsNotDegraded() throws Exception {
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/shipments/by-order/" + orderId)).willReturn(aResponse().withStatus(404)));
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/tracking/orders/" + orderId)).willReturn(aResponse().withStatus(404)));

        details()
                .andExpect(jsonPath("$.shipment").value(nullValue()))
                .andExpect(jsonPath("$.tracking").value(nullValue()))
                .andExpect(jsonPath("$.degraded").value(false));
    }

    @Test
    void theFourCallsRunInParallel() throws Exception {
        int delay = 200;   // under the 300 ms read timeout
        stubUserWithDelay(delay);
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/products")).willReturn(okJson(PRODUCTS_JSON).withFixedDelay(delay)));
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/shipments/by-order/" + orderId)).willReturn(shipment().withFixedDelay(delay)));
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/tracking/orders/" + orderId)).willReturn(tracking().withFixedDelay(delay)));

        long start = System.nanoTime();
        details().andExpect(jsonPath("$.degraded").value(false));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // sequential: 4 × 200 = 800 ms; in parallel about the slowest call
        assertThat(elapsedMs).isGreaterThanOrEqualTo(delay).isLessThan(600);
    }

    @Test
    void someoneElsesOrderIsNotFound() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/orders/{id}/details", orderId)
                        .header("X-Demo-User-Id", "00000000-0000-4000-8000-0000000000c2"))
                .andExpect(status().isNotFound());
    }

    private ResultActions details() throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/orders/{id}/details", orderId).header("X-Demo-User-Id", CUSTOMER));
    }

    private void stubUserWithDelay(int delay) {
        DOWNSTREAM.stubFor(get(urlPathMatching("/api/users/.*")).willReturn(okJson("""
                {"id":"%s","email":"customer@demo.local","fullName":"Demo Customer","status":"ACTIVE","defaultAddress":null}"""
                .formatted(CUSTOMER)).withFixedDelay(delay)));
    }

    private ResponseDefinitionBuilder shipment() {
        return okJson("""
                {"trackingNumber":"SMDABC","carrier":"DEMO-EXPRESS","status":"IN_TRANSIT","estimatedDelivery":"2026-10-08"}""");
    }

    private ResponseDefinitionBuilder tracking() {
        return okJson("""
                {"orderId":"%s","currentStatus":"SHIPMENT_IN_TRANSIT","timeline":[
                  {"status":"ORDER_CONFIRMED","source":"order-service","occurredAt":"2026-10-05T10:00:00.000Z","details":{}},
                  {"status":"SHIPMENT_IN_TRANSIT","source":"shipping-service","occurredAt":"2026-10-05T10:00:10.000Z","details":{}}]}"""
                .formatted(orderId));
    }
}
