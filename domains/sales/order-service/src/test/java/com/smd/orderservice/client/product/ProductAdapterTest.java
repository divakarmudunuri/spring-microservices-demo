package com.smd.orderservice.client.product;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.smd.orderservice.client.DependencyUnavailableException;
import com.smd.orderservice.client.FeignClientTest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** CLAUDE.md 6.3: success, 404, 500 with retry, slow → timeout, circuit opening. */
class ProductAdapterTest extends FeignClientTest {

    static final String PATH = "/api/products";

    @Autowired
    ProductAdapter products;

    @Test
    void successSendsTheIdsAndMapsTheProducts() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(PRODUCTS_JSON)));

        Map<UUID, ProductInfo> found = products.getProducts(List.of(EARBUDS, KEYBOARD));

        assertThat(found.get(KEYBOARD).price()).isEqualByComparingTo("129.00");
        assertThat(found.get(EARBUDS).name()).isEqualTo("Wireless Earbuds");
        DOWNSTREAM.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("ids", WireMock.equalTo(EARBUDS.toString()))
                .withQueryParam("ids", WireMock.equalTo(KEYBOARD.toString())));
    }

    @Test
    void notFoundMeansTheEndpointIsMissingAndIsNotRetried() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> products.getProducts(List.of(EARBUDS))).isInstanceOf(DependencyUnavailableException.class);
        DOWNSTREAM.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void serverErrorIsRetriedAndThenSucceeds() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(502)).willSetStateTo("one-down"));
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("one-down")
                .willReturn(aResponse().withStatus(500)).willSetStateTo("recovered"));
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson(PRODUCTS_JSON)));

        assertThat(products.getProducts(List.of(EARBUDS))).containsKey(EARBUDS);
        DOWNSTREAM.verify(3, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void slowResponseTimesOutAndIsRetried() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(PRODUCTS_JSON).withFixedDelay(1_000)));

        assertThatThrownBy(() -> products.getProducts(List.of(EARBUDS)))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
        DOWNSTREAM.verify(3, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> products.getProducts(List.of(EARBUDS))).isInstanceOf(DependencyUnavailableException.class);
        }
        assertThat(circuitBreakers.circuitBreaker(ProductAdapter.RESILIENCE).getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // call 1: 3 failed attempts; call 2: the 4th failure fills the window and opens the circuit,
        // so its next attempt is refused without reaching product-service
        DOWNSTREAM.verify(4, getRequestedFor(urlPathEqualTo(PATH)));

        assertThatThrownBy(() -> products.getProducts(List.of(EARBUDS)))
                .hasCauseInstanceOf(CallNotPermittedException.class);
        DOWNSTREAM.verify(4, getRequestedFor(urlPathEqualTo(PATH)));   // fails fast: no new request
    }
}
