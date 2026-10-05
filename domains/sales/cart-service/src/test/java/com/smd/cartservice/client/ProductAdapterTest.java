package com.smd.cartservice.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smd.cartservice.CartIntegrationTest;
import com.smd.cartservice.client.product.ProductAdapter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** CLAUDE.md 6.3: success, 404/4xx, 500 with retry, slow → timeout, circuit opening. */
class ProductAdapterTest extends CartIntegrationTest {

    static final String PATH = "/api/products";

    @Autowired
    ProductAdapter products;

    @Test
    void successIsMapped() {
        assertThat(products.getProducts(List.of(EARBUDS, MONITOR)).get(MONITOR).outOfStock()).isTrue();
    }

    @Test
    void clientErrorsAreNotRetried() {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> products.getProducts(List.of(EARBUDS))).isInstanceOf(ProductsUnavailableException.class);
        PRODUCT_SERVICE.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void serverErrorIsRetried() {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("ok"));
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("ok")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(CATALOG)));

        assertThat(products.getProducts(List.of(EARBUDS))).containsKey(EARBUDS);
        PRODUCT_SERVICE.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void slowResponseTimesOutAfterThreeAttempts() {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(CATALOG).withFixedDelay(1_000)));

        assertThatThrownBy(() -> products.getProducts(List.of(EARBUDS))).isInstanceOf(ProductsUnavailableException.class);
        PRODUCT_SERVICE.verify(3, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> products.getProducts(List.of(EARBUDS))).isInstanceOf(ProductsUnavailableException.class);
        }

        assertThatThrownBy(() -> products.getProducts(List.of(EARBUDS))).hasCauseInstanceOf(CallNotPermittedException.class);
        PRODUCT_SERVICE.verify(4, getRequestedFor(urlPathEqualTo(PATH)));   // window of 4: fails fast afterwards
    }
}
