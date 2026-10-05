package com.smd.orderservice.checkout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smd.orderservice.OrderServiceIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** CLAUDE.md 6.12: checkout from the cart runs exactly the 6.1 flow with the cart's lines. */
class CheckoutFromCartTest extends OrderServiceIntegrationTest {

    static final UUID CART_ID = UUID.fromString("7d0c5b1e-2f6a-4f3e-8a1b-5c9e2d4f6a70");
    static final Instant CREATED = Instant.parse("2026-10-05T09:00:00.123Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void theCartBecomesAConfirmedOrderThatRemembersTheCart() throws Exception {
        stubCart(3, "{\"productId\":\"" + EARBUDS + "\",\"quantity\":2},{\"productId\":\"" + CHARGER + "\",\"quantity\":1}");

        String body = checkout(null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cartId").value(CART_ID.toString()))
                .andExpect(jsonPath("$.totalAmount").value(199.97))
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        assertThat(stock(EARBUDS)).isEqualTo(38);
        // ORDER_CONFIRMED carries the cart id: that is how cart-service knows which cart to empty
        assertThat(jdbc.sql("SELECT payload->'payload'->>'cartId' FROM outbox_event WHERE event_type = 'ORDER_CONFIRMED' AND aggregate_id = ?")
                .param(orderId).query(String.class).single()).isEqualTo(CART_ID.toString());
        // the cart was read with the customer's own token, and never changed by order-service
        DOWNSTREAM.verify(getRequestedFor(urlPathEqualTo("/api/cart")).withHeader("Authorization", equalTo("Bearer " + tokenFor(CUSTOMER))));
        DOWNSTREAM.verify(0, postRequestedFor(urlPathMatching("/api/cart.*")));
    }

    @Test
    void theSameCartVersionCheckedOutTwiceGivesOneOrder() throws Exception {
        stubCart(7, "{\"productId\":\"" + EARBUDS + "\",\"quantity\":1}");

        String first = checkout(null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = checkout(null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("id")).isEqualTo(objectMapper.readTree(first).get("id"));
        assertThat(count("orders")).isOne();
        assertThat(jdbc.sql("SELECT idempotency_key FROM orders").query(String.class).single()).isEqualTo("cart:" + CART_ID + ":" + CREATED.toEpochMilli() + ":v7");
    }

    @Test
    void aChangedCartIsANewOrder() throws Exception {
        stubCart(7, "{\"productId\":\"" + EARBUDS + "\",\"quantity\":1}");
        checkout(null).andExpect(status().isCreated());
        stubCart(8, "{\"productId\":\"" + CHARGER + "\",\"quantity\":1}");
        checkout(null).andExpect(status().isCreated());

        assertThat(count("orders")).isEqualTo(2);
    }

    @Test
    void aRecreatedCartWithTheSameIdAndVersionIsANewOrder() throws Exception {
        // a customer's cart id is derived from the user id, and an emptied cart is deleted: the next one starts over
        stubCart(2, CREATED, "{\"productId\":\"" + EARBUDS + "\",\"quantity\":1}");
        checkout(null).andExpect(status().isCreated());
        stubCart(2, CREATED.plusSeconds(3600), "{\"productId\":\"" + CHARGER + "\",\"quantity\":1}");
        checkout(null).andExpect(status().isCreated());

        assertThat(count("orders")).isEqualTo(2);
    }

    @Test
    void anEmptyCartIsRecordedAndRejected() throws Exception {
        stubCart(1, "");

        String body = checkout(null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("/problems/empty-cart"))
                .andExpect(jsonPath("$.reason").value("EMPTY_CART"))
                .andReturn().getResponse().getContentAsString();

        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("orderId").asText());
        assertThat(statuses()).containsExactly("REJECTED");
        assertThat(orderEvents(orderId)).containsExactly("ORDER_INITIATED", "ORDER_REJECTED");
    }

    @Test
    void outOfStockAtCheckoutIs409AndNothingTouchesTheCart() throws Exception {
        stubCart(2, "{\"productId\":\"" + MONITOR + "\",\"quantity\":1}");

        checkout(null).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("OUT_OF_STOCK"));

        DOWNSTREAM.verify(0, postRequestedFor(urlPathMatching("/api/cart.*")));
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE event_type = 'ORDER_CONFIRMED'").query(Integer.class).single()).isZero();
    }

    @Test
    void aClientIdempotencyKeyWinsOverTheDerivedOne() throws Exception {
        stubCart(4, "{\"productId\":\"" + EARBUDS + "\",\"quantity\":1}");

        checkout("my-key").andExpect(status().isCreated());

        assertThat(jdbc.sql("SELECT idempotency_key FROM orders").query(String.class).single()).isEqualTo("my-key");
    }

    @Test
    void cartServiceDownIs503() throws Exception {
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/cart")).willReturn(aResponse().withStatus(503)));

        checkout(null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("/problems/dependency-unavailable"));
        assertThat(count("orders")).as("no cart, no order").isZero();
    }

    @Test
    void onlyCustomersCheckOut() throws Exception {
        mvc.perform(post("/api/orders/checkout").with(admin())).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/checkout")).andExpect(status().isUnauthorized());
    }

    private ResultActions checkout(String idempotencyKey) throws Exception {
        var request = post("/api/orders/checkout").with(customer(CUSTOMER));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private static void stubCart(long version, String lines) {
        stubCart(version, CREATED, lines);
    }

    private static void stubCart(long version, Instant createdAt, String lines) {
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/cart")).willReturn(okJson("""
                {"cartId":"%s","guest":false,"version":%d,"lines":[%s],"subtotal":0,"currency":"USD","degraded":false,
                 "createdAt":"%s"}"""
                .formatted(CART_ID, version, lines, createdAt))));
    }
}
