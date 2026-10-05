package com.smd.cartservice.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smd.cartservice.CartIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** CLAUDE.md 6.12 cart tests: guest carts, customer carts, merge, limits, the live view. */
class CartApiTest extends CartIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void guestCreatesAddsUpdatesRemovesAndClears() throws Exception {
        String cartId = newGuestCart();

        add(cartId, null, EARBUDS, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.guest").value(true))
                .andExpect(jsonPath("$.lines[0].name").value("Wireless Earbuds"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(79.99))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(159.98))
                .andExpect(jsonPath("$.lines[0].availability").value("IN_STOCK"));
        add(cartId, null, EARBUDS, 1).andExpect(jsonPath("$.lines[0].quantity").value(3));   // adds to the line
        add(cartId, null, CHARGER, 1).andExpect(jsonPath("$.subtotal").value(279.96));
        mvc.perform(put("/api/cart/items/{p}", EARBUDS).header("X-Cart-Id", cartId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(jsonPath("$.lines[0].quantity").value(5));
        mvc.perform(put("/api/cart/items/{p}", EARBUDS).header("X-Cart-Id", cartId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))   // 0 removes
                .andExpect(jsonPath("$.lines", hasSize(1)));
        mvc.perform(delete("/api/cart/items/{p}", CHARGER).header("X-Cart-Id", cartId))
                .andExpect(jsonPath("$.lines", hasSize(0)))
                .andExpect(jsonPath("$.subtotal").value(0));
        mvc.perform(delete("/api/cart").header("X-Cart-Id", cartId)).andExpect(status().isNoContent());
        view(cartId, null).andExpect(status().isNotFound());
    }

    @Test
    void aGuestCartIsOnlyReachableWithItsId() throws Exception {
        String cartId = newGuestCart();
        add(cartId, null, EARBUDS, 1).andExpect(status().isOk());

        view(null, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value("/problems/cart-not-found"));
        view(UUID.randomUUID().toString(), null).andExpect(status().isNotFound());
        view(cartId, null).andExpect(status().isOk());
    }

    @Test
    void aCustomerCartIsNeverReachableAsAGuestCart() throws Exception {
        UUID customer = UUID.randomUUID();
        String customerCartId = json(view(null, customer(customer))).get("cartId").asText();

        view(customerCartId, null).andExpect(status().isNotFound());   // even knowing the id
    }

    @Test
    void aCustomerAlwaysGetsTheirOneCartAndXCartIdIsIgnored() throws Exception {
        UUID customer = UUID.randomUUID();
        String guestCart = newGuestCart();

        String first = json(view(null, customer(customer)).andExpect(jsonPath("$.guest").value(false))).get("cartId").asText();
        String second = json(view(guestCart, customer(customer))).get("cartId").asText();
        String viaPost = json(mvc.perform(post("/api/cart").with(customer(customer))).andExpect(status().isOk())).get("cartId").asText();

        assertThat(second).isEqualTo(first);
        assertThat(viaPost).isEqualTo(first);
    }

    @Test
    void adminsHaveNoCart() throws Exception {
        view(null, admin()).andExpect(status().isForbidden());
        mvc.perform(post("/api/cart").with(admin())).andExpect(status().isForbidden());
    }

    @Test
    void anInvalidTokenIs401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/cart").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void limitsAreEnforced() throws Exception {
        String cartId = newGuestCart();
        add(cartId, null, EARBUDS, 11).andExpect(status().isBadRequest());           // one request: at most 10
        add(cartId, null, EARBUDS, 6).andExpect(status().isOk());
        add(cartId, null, EARBUDS, 5).andExpect(status().isUnprocessableEntity())     // 11 of one product
                .andExpect(jsonPath("$.type").value("/problems/cart-limit"));
        add(cartId, null, CHARGER, 1);
        add(cartId, null, KEYBOARD, 1);
        add(cartId, null, BOTTLE, 1).andExpect(status().isUnprocessableEntity());     // 4th line, limit 3 in tests
    }

    @Test
    void outOfStockAndUnknownProductsCantBeAdded() throws Exception {
        String cartId = newGuestCart();
        add(cartId, null, MONITOR, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/out-of-stock"));
        add(cartId, null, UUID.randomUUID(), 1).andExpect(status().isNotFound());
    }

    @Test
    void withoutProductServiceTheCartIsShownDegradedNotFailed() throws Exception {
        String cartId = newGuestCart();
        add(cartId, null, EARBUDS, 2);
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo("/api/products")).willReturn(aResponse().withStatus(503)));

        view(cartId, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(nullValue()))
                .andExpect(jsonPath("$.subtotal").value(nullValue()));
        add(cartId, null, CHARGER, 1).andExpect(status().isServiceUnavailable());   // can't check the stock level
    }

    @Test
    void mergeSumsQuantitiesCapsThemAndDeletesTheGuestCart() throws Exception {
        UUID customer = UUID.randomUUID();
        add(null, customer(customer), EARBUDS, 9);
        String guest = newGuestCart();
        add(guest, null, EARBUDS, 2);
        add(guest, null, CHARGER, 1);

        merge(guest, customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.guest").value(false))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[?(@.productId=='" + EARBUDS + "')].quantity").value(10))   // 9 + 2, capped
                .andExpect(jsonPath("$.lines[?(@.productId=='" + CHARGER + "')].quantity").value(1));
        view(guest, null).andExpect(status().isNotFound());   // the guest cart is gone
    }

    @Test
    void mergingTwiceOrAMissingCartIsSafe() throws Exception {
        UUID customer = UUID.randomUUID();
        String guest = newGuestCart();
        add(guest, null, CHARGER, 2);

        merge(guest, customer).andExpect(jsonPath("$.lines[0].quantity").value(2));
        merge(guest, customer).andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quantity").value(2));   // unchanged
        merge(UUID.randomUUID().toString(), customer).andExpect(status().isOk()).andExpect(jsonPath("$.lines", hasSize(1)));
        mvc.perform(post("/api/cart/merge").header("X-Cart-Id", guest)).andExpect(status().isUnauthorized());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private String newGuestCart() throws Exception {
        return mvc.perform(post("/api/cart"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Cart-Id"))
                .andReturn().getResponse().getHeader("X-Cart-Id");
    }

    private ResultActions add(String cartId, RequestPostProcessor who, UUID productId, int quantity) throws Exception {
        var request = post("/api/cart/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}");
        if (cartId != null) {
            request.header("X-Cart-Id", cartId);
        }
        if (who != null) {
            request.with(who);
        }
        return mvc.perform(request);
    }

    private ResultActions view(String cartId, RequestPostProcessor who) throws Exception {
        var request = MockMvcRequestBuilders.get("/api/cart");
        if (cartId != null) {
            request.header("X-Cart-Id", cartId);
        }
        if (who != null) {
            request.with(who);
        }
        return mvc.perform(request);
    }

    private ResultActions merge(String guestCartId, UUID customer) throws Exception {
        return mvc.perform(post("/api/cart/merge").header("X-Cart-Id", guestCartId).with(customer(customer)));
    }

    private com.fasterxml.jackson.databind.JsonNode json(ResultActions result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.andReturn().getResponse().getContentAsString());
    }
}
