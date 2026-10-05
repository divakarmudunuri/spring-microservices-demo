package com.smd.storefrontbff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smd.storefrontbff.client.product.ProductAdapter;
import com.smd.storefrontbff.client.product.ProductNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** CLAUDE.md 6.3: success, 404, 500 with retry, slow → timeout, circuit opening (2 attempts in the BFF). */
class ProductAdapterTest extends BffIntegrationTest {

    static final String PATH = "/api/categories";

    @Autowired
    ProductAdapter products;

    @Test
    void success() {
        assertThat(products.categories()).hasSize(2);
        assertThat(products.product("mechanical-keyboard").availability()).isEqualTo("LOW_STOCK");
    }

    @Test
    void notFoundIsADomainExceptionAndNotRetried() {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo("/api/products/nope")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> products.product("nope")).isInstanceOf(ProductNotFoundException.class);
        PRODUCT_SERVICE.verify(1, getRequestedFor(urlPathEqualTo("/api/products/nope")));
    }

    @Test
    void serverErrorIsRetried() {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("ok"));
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("ok").willReturn(json(CATEGORIES)));

        assertThat(products.categories()).hasSize(2);
        PRODUCT_SERVICE.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void slowResponseTimesOut() {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(PATH)).willReturn(json(CATEGORIES).withFixedDelay(1_500)));

        assertThatThrownBy(() -> products.categories()).isNotNull();
        PRODUCT_SERVICE.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> products.categories()).isNotNull();   // 2 attempts each: 4 recorded failures
        }

        assertThatThrownBy(() -> products.categories()).isInstanceOf(CallNotPermittedException.class);
        PRODUCT_SERVICE.verify(4, getRequestedFor(urlPathEqualTo(PATH)));
    }
}
