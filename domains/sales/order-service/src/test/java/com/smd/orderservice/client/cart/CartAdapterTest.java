package com.smd.orderservice.client.cart;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smd.orderservice.client.DependencyUnavailableException;
import com.smd.orderservice.client.FeignClientTest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** CLAUDE.md 6.3: success, 404, 500 with retry, slow → timeout, circuit opening. */
class CartAdapterTest extends FeignClientTest {

    static final String PATH = "/api/cart";
    static final String CART = """
            {"cartId":"7d0c5b1e-2f6a-4f3e-8a1b-5c9e2d4f6a70","version":3,"lines":[{"productId":"%s","quantity":2}]}"""
            .formatted(EARBUDS);

    @Autowired
    CartAdapter carts;

    @Test
    void successIsMapped() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(CART)));

        Cart cart = carts.getMyCart();

        assertThat(cart.version()).isEqualTo(3);
        assertThat(cart.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualTo(2));
    }

    @Test
    void notFoundIsNotRetried() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> carts.getMyCart()).isInstanceOf(DependencyUnavailableException.class);
        DOWNSTREAM.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void serverErrorIsRetried() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("ok"));
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("ok").willReturn(okJson(CART)));

        assertThat(carts.getMyCart().version()).isEqualTo(3);
        DOWNSTREAM.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void slowResponseTimesOutAfterThreeAttempts() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(CART).withFixedDelay(1_000)));

        assertThatThrownBy(() -> carts.getMyCart()).isInstanceOf(DependencyUnavailableException.class);
        DOWNSTREAM.verify(3, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> carts.getMyCart()).isInstanceOf(DependencyUnavailableException.class);
        }

        assertThatThrownBy(() -> carts.getMyCart()).hasCauseInstanceOf(CallNotPermittedException.class);
        DOWNSTREAM.verify(4, getRequestedFor(urlPathEqualTo(PATH)));
    }
}
