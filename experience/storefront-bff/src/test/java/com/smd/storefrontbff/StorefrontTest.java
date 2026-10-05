package com.smd.storefrontbff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** CLAUDE.md 6.12 BFF tests. Each test gets a fresh context, so the 30 s home cache starts empty. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class StorefrontTest extends BffIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @Test
    void homeHasCategoriesFeaturedAndNewArrivals() throws Exception {
        mvc.perform(get("/api/storefront/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[*].slug", contains("electronics", "home-kitchen")))
                .andExpect(jsonPath("$.featured[*].slug", contains("wireless-earbuds", "mechanical-keyboard")))
                .andExpect(jsonPath("$.newArrivals[0].availability").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.unavailableSections", empty()));
    }

    @Test
    void theThreeCallsRunInParallel() throws Exception {
        stubCategories(json(CATEGORIES).withFixedDelay(300));
        stubFeatured(json(page(EARBUDS)).withFixedDelay(300));
        stubNewest(json(page(MONITOR)).withFixedDelay(300));

        long start = System.nanoTime();
        mvc.perform(get("/api/storefront/home")).andExpect(jsonPath("$.degraded").value(false));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // sequential: 3 × 300 = 900 ms; in parallel about the slowest call
        assertThat(elapsedMs).isGreaterThanOrEqualTo(300).isLessThan(700);
    }

    @Test
    void aFailingSectionDegradesOnlyItself() throws Exception {
        stubCategories(aResponse().withStatus(500));

        mvc.perform(get("/api/storefront/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.unavailableSections", contains("categories")))
                .andExpect(jsonPath("$.categories", empty()))
                .andExpect(jsonPath("$.featured", hasSize(2)));
    }

    @Test
    void productServiceDownGivesAFullyDegradedHomeNotAnError() throws Exception {
        PRODUCT_SERVICE.stop();
        try {
            mvc.perform(get("/api/storefront/home"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unavailableSections", contains("categories", "featured", "newArrivals")));
        } finally {
            PRODUCT_SERVICE.start();
        }
    }

    @Test
    void theAnonymousHomePageIsCachedButADegradedOneIsNot() throws Exception {
        stubCategories(aResponse().withStatus(500));
        mvc.perform(get("/api/storefront/home")).andExpect(jsonPath("$.degraded").value(true));
        stubCategories(json(CATEGORIES));
        // in these tests the breaker's window is only 4 calls: the failed attempts above just opened it
        circuitBreakers.circuitBreaker("productService").reset();

        mvc.perform(get("/api/storefront/home")).andExpect(jsonPath("$.degraded").value(false));   // not stuck degraded
        mvc.perform(get("/api/storefront/home")).andExpect(jsonPath("$.degraded").value(false));
        mvc.perform(get("/api/storefront/home")).andExpect(jsonPath("$.degraded").value(false));

        // first call failed (2 attempts), second filled the cache, third and fourth came from it
        PRODUCT_SERVICE.verify(3, getRequestedFor(urlPathEqualTo("/api/categories")));
    }

    @Test
    void productPageHasTheProductRelatedProductsAndCategories() throws Exception {
        mvc.perform(get("/api/storefront/products/mechanical-keyboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.related[*].slug", contains("wireless-earbuds", "monitor-27-4k")))   // not itself
                .andExpect(jsonPath("$.categories", hasSize(2)))
                .andExpect(jsonPath("$.degraded").value(false));
    }

    @Test
    void unknownProductIs404AndAMissingCatalogIs503() throws Exception {
        PRODUCT_SERVICE.stubFor(WireMock.get(urlPathEqualTo("/api/products/nope"))
                .willReturn(aResponse().withStatus(404)));
        mvc.perform(get("/api/storefront/products/nope")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/product-not-found"));

        PRODUCT_SERVICE.stubFor(WireMock.get(urlPathEqualTo("/api/products/mechanical-keyboard"))
                .willReturn(aResponse().withStatus(503)));
        mvc.perform(get("/api/storefront/products/mechanical-keyboard")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void anInvalidTokenIs401EvenOnThePublicStorefront() throws Exception {
        mvc.perform(get("/api/storefront/home").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
    }
}
