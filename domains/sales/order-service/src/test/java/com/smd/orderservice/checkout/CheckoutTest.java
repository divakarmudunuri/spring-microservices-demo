package com.smd.orderservice.checkout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.smd.orderservice.OrderServiceIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** CLAUDE.md 6.1: the checkout flow end to end, against a real Postgres (not mocks). */
class CheckoutTest extends OrderServiceIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void happyPathTakesStockAndMoneyAndConfirmsInOneGo() throws Exception {
        String body = placeOrder(CUSTOMER, "key-happy", item(EARBUDS, 2), item(CHARGER, 1))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(199.97))
                .andExpect(jsonPath("$.shippingAddress.city").value("Detroit"))
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        assertThat(stock(EARBUDS)).isEqualTo(38);
        assertThat(stock(CHARGER)).isEqualTo(99);
        assertThat(balance(CUSTOMER)).isEqualByComparingTo("300.03");
        assertThat(jdbc.sql("SELECT status, amount FROM payments WHERE order_id = ?").param(orderId)
                .query((rs, n) -> rs.getString(1) + " " + rs.getBigDecimal(2)).single()).isEqualTo("CAPTURED 199.97");
        assertThat(jdbc.sql("SELECT count(*) FROM wallet_transactions WHERE order_id = ? AND type = 'PAYMENT'")
                .param(orderId).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT sum(delta) FROM stock_movements WHERE order_id = ?").param(orderId)
                .query(Integer.class).single()).isEqualTo(-3);
        assertThat(jdbc.sql("SELECT unit_price FROM order_items WHERE order_id = ? AND product_id = ?")
                .param(orderId).param(EARBUDS).query(BigDecimal.class).single()).isEqualByComparingTo("79.99");

        assertThat(orderEvents(orderId)).containsExactly(
                "ORDER_INITIATED", "INVENTORY_RESERVED", "PAYMENT_CAPTURED", "ORDER_CONFIRMED");
        assertThat(jdbc.sql("""
                        SELECT payload->'payload'->>'quantityOnHand' FROM outbox_event
                         WHERE topic = 'inventory-events' ORDER BY aggregate_id""")
                .query(String.class).list()).containsExactly("38", "99");

        JsonNode confirmed = objectMapper.readTree(jdbc.sql("""
                        SELECT payload::text FROM outbox_event WHERE aggregate_id = ? AND event_type = 'ORDER_CONFIRMED'""")
                .param(orderId).query(String.class).single());
        assertThat(confirmed.get("userId").asText()).isEqualTo(CUSTOMER.toString());
        assertThat(confirmed.get("source").asText()).isEqualTo("order-service");
        assertThat(confirmed.at("/payload/totalAmount").decimalValue()).isEqualByComparingTo("199.97");
        assertThat(confirmed.at("/payload/shippingAddress/postalCode").asText()).isEqualTo("48226");
        assertThat(confirmed.at("/payload/items")).hasSize(2);
    }

    @Test
    void secondItemOutOfStockRollsBackTheFirstItem() throws Exception {
        // EARBUDS sorts before MONITOR, so the earbuds are decremented first, then the monitor (stock 0) fails
        String body = placeOrder(CUSTOMER, "key-oos", item(EARBUDS, 1), item(MONITOR, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/out-of-stock"))
                .andExpect(jsonPath("$.reason").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.orderId").exists())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("orderId").asText());

        assertThat(stock(EARBUDS)).as("first item's stock is unchanged").isEqualTo(40);
        assertThat(stock(MONITOR)).isZero();
        assertThat(balance(CUSTOMER)).isEqualByComparingTo("500.00");
        assertThat(count("payments")).isZero();
        assertThat(count("stock_movements")).isZero();
        assertThat(count("wallet_transactions")).as("only the seeded top-up").isOne();
        assertThat(statuses()).containsExactly("REJECTED");
        assertThat(orderEvents(orderId)).containsExactly("ORDER_INITIATED", "ORDER_REJECTED");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE topic = 'inventory-events'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void insufficientFundsLeavesEveryItemsStockUnchanged() throws Exception {
        // 6 × 79.99 + 5 × 39.99 = 679.89 > 500.00
        placeOrder(CUSTOMER, "key-funds", item(EARBUDS, 6), item(CHARGER, 5))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.type").value("/problems/insufficient-funds"));

        assertThat(stock(EARBUDS)).isEqualTo(40);
        assertThat(stock(CHARGER)).isEqualTo(100);
        assertThat(balance(CUSTOMER)).isEqualByComparingTo("500.00");
        assertThat(count("payments")).isZero();
        assertThat(statuses()).containsExactly("REJECTED");
    }

    @Test
    void sameIdempotencyKeyTwiceGivesOneOrderAndOnePayment() throws Exception {
        String first = placeOrder(CUSTOMER, "key-twice", item(EARBUDS, 1)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = placeOrder(CUSTOMER, "key-twice", item(EARBUDS, 1)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("id")).isEqualTo(objectMapper.readTree(first).get("id"));
        assertThat(count("orders")).isOne();
        assertThat(count("payments")).isOne();
        assertThat(stock(EARBUDS)).isEqualTo(39);
        assertThat(balance(CUSTOMER)).isEqualByComparingTo("420.01");
    }

    @Test
    void replayingARejectedOrderReturnsTheSameRejection() throws Exception {
        placeOrder(CUSTOMER, "key-rejected", item(MONITOR, 1)).andExpect(status().isConflict());
        placeOrder(CUSTOMER, "key-rejected", item(MONITOR, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("OUT_OF_STOCK"));

        assertThat(count("orders")).isOne();
    }

    @Test
    void anIdempotencyKeyCannotBeReusedByAnotherCustomer() throws Exception {
        UUID other = UUID.fromString("00000000-0000-4000-8000-0000000000c2");
        placeOrder(CUSTOMER, "key-shared", item(EARBUDS, 1)).andExpect(status().isCreated());

        placeOrder(other, "key-shared", item(EARBUDS, 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));
    }

    @Test
    void userServiceDownFailsTheOrderWithDependencyUnavailable() throws Exception {
        DOWNSTREAM.stubFor(WireMock.get(urlPathMatching("/api/users/.*")).willReturn(aResponse().withStatus(500)));

        String body = placeOrder(CUSTOMER, "key-down", item(EARBUDS, 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("DEPENDENCY_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("orderId").asText());

        assertThat(statuses()).containsExactly("FAILED");
        assertThat(orderEvents(orderId)).containsExactly("ORDER_INITIATED", "ORDER_FAILED");
        assertThat(stock(EARBUDS)).isEqualTo(40);
    }

    @Test
    void unknownProductIsRejected() throws Exception {
        placeOrder(CUSTOMER, "key-unknown", item(UUID.fromString("20000000-0000-4000-8000-00000000dead"), 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PRODUCT_NOT_FOUND"));
        assertThat(statuses()).containsExactly("REJECTED");
    }

    @Test
    void customerWithoutAddressIsRejected() throws Exception {
        stubUser(CUSTOMER, "ACTIVE", false);

        placeOrder(CUSTOMER, "key-no-address", item(EARBUDS, 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_SHIPPING_ADDRESS"));
    }

    @Test
    void suspendedOrUnknownUserIsRejectedAsInactive() throws Exception {
        stubUser(CUSTOMER, "SUSPENDED", true);
        placeOrder(CUSTOMER, "key-suspended", item(EARBUDS, 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("USER_INACTIVE"));

        UUID ghost = UUID.fromString("00000000-0000-4000-8000-00000000dead");
        DOWNSTREAM.stubFor(WireMock.get(urlPathMatching("/api/users/" + ghost)).willReturn(aResponse().withStatus(404)));
        placeOrder(ghost, "key-ghost", item(EARBUDS, 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("USER_INACTIVE"));
    }

    @Test
    void lookupsRunInParallel() throws Exception {
        DOWNSTREAM.stubFor(WireMock.get(urlPathMatching("/api/users/.*")).willReturn(okJson("""
                {"id":"%s","fullName":"Demo Customer","status":"ACTIVE","defaultAddress":
                 {"fullName":"Demo Customer","line1":"1 St","city":"Detroit","state":"MI","postalCode":"48226","country":"US"}}"""
                .formatted(CUSTOMER)).withFixedDelay(700)));
        DOWNSTREAM.stubFor(WireMock.get(urlPathEqualTo("/api/products")).willReturn(okJson(PRODUCTS_JSON).withFixedDelay(700)));

        long start = System.nanoTime();
        placeOrder(CUSTOMER, "key-parallel", item(EARBUDS, 1)).andExpect(status().isCreated());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // sequential would be ≥ 1400 ms; in parallel the total is about the slowest call
        assertThat(elapsedMs).isGreaterThanOrEqualTo(700).isLessThan(1300);
    }

    @Test
    void correlationIdReachesBothDownstreamCallsFromTheWorkerThreads() throws Exception {
        mvc.perform(post("/api/orders")
                        .header("X-Demo-User-Id", CUSTOMER)
                        .header("Idempotency-Key", "key-corr")
                        .header("X-Correlation-Id", "corr-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(item(EARBUDS, 1))))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-Id", "corr-123"));

        DOWNSTREAM.verify(getRequestedFor(urlPathMatching("/api/users/.*")).withHeader("X-Correlation-Id", equalTo("corr-123")));
        DOWNSTREAM.verify(getRequestedFor(urlPathEqualTo("/api/products")).withHeader("X-Correlation-Id", equalTo("corr-123")));
    }

    @Test
    void malformedRequestsAreRejectedWithoutRecordingAnOrder() throws Exception {
        mvc.perform(post("/api/orders").header("X-Demo-User-Id", CUSTOMER)
                        .contentType(MediaType.APPLICATION_JSON).content(json(item(EARBUDS, 1))))
                .andExpect(status().isBadRequest());                                   // no Idempotency-Key
        placeOrder(CUSTOMER, "key-dup", item(EARBUDS, 1), item(EARBUDS, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invalid-order"));       // same product twice
        placeOrder(CUSTOMER, "key-qty", item(EARBUDS, 11)).andExpect(status().isBadRequest());
        placeOrder(CUSTOMER, "key-empty").andExpect(status().isBadRequest());

        assertThat(count("orders")).isZero();
    }

    @Test
    void customersSeeOnlyTheirOwnOrders() throws Exception {
        String body = placeOrder(CUSTOMER, "key-own", item(EARBUDS, 1)).andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(body).get("id").asText();

        mvc.perform(get("/api/orders/{id}", orderId).header("X-Demo-User-Id", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(79.99));
        mvc.perform(get("/api/orders/{id}", orderId).header("X-Demo-User-Id", "00000000-0000-4000-8000-0000000000c2"))
                .andExpect(status().isNotFound());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private ResultActions placeOrder(UUID userId, String idempotencyKey, String... items) throws Exception {
        return mvc.perform(post("/api/orders")
                .header("X-Demo-User-Id", userId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(items)));
    }

    private static String item(UUID productId, int quantity) {
        return "{\"productId\":\"%s\",\"quantity\":%d}".formatted(productId, quantity);
    }

    private static String json(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }
}
