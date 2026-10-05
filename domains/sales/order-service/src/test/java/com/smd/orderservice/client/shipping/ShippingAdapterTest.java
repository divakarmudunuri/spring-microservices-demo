package com.smd.orderservice.client.shipping;

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
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** CLAUDE.md 6.3: success, 404, 500 with retry, slow → timeout, circuit opening. */
class ShippingAdapterTest extends FeignClientTest {

    static final UUID ORDER = UUID.fromString("40000000-0000-4000-8000-000000000001");
    static final String PATH = "/api/shipments/by-order/" + ORDER;
    static final String SHIPMENT = """
            {"id":"%s","orderId":"%s","trackingNumber":"SMDABC","carrier":"DEMO-EXPRESS","status":"IN_TRANSIT",
             "estimatedDelivery":"2026-10-08","deliveredAt":null}""".formatted(UUID.randomUUID(), ORDER);

    @Autowired
    ShippingAdapter shipping;

    @Test
    void successIsMapped() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(SHIPMENT)));

        assertThat(shipping.findShipment(ORDER)).get()
                .satisfies(s -> {
                    assertThat(s.trackingNumber()).isEqualTo("SMDABC");
                    assertThat(s.status()).isEqualTo("IN_TRANSIT");
                    assertThat(s.estimatedDelivery()).hasToString("2026-10-08");
                });
    }

    @Test
    void notFoundMeansNoShipmentYetAndIsNotAFailure() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(shipping.findShipment(ORDER)).isEmpty();
        DOWNSTREAM.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void serverErrorIsRetried() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("ok"));
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("ok").willReturn(okJson(SHIPMENT)));

        assertThat(shipping.findShipment(ORDER)).isPresent();
        DOWNSTREAM.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void slowResponseTimesOutAfterThreeAttempts() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(SHIPMENT).withFixedDelay(1_000)));

        assertThatThrownBy(() -> shipping.findShipment(ORDER)).isInstanceOf(DependencyUnavailableException.class);
        DOWNSTREAM.verify(3, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> shipping.findShipment(ORDER)).isInstanceOf(DependencyUnavailableException.class);
        }
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> shipping.findShipment(ORDER)).hasCauseInstanceOf(CallNotPermittedException.class);
        DOWNSTREAM.verify(4, getRequestedFor(urlPathEqualTo(PATH)));
    }

    private CircuitBreaker breaker() {
        return circuitBreakers.circuitBreaker(ShippingAdapter.RESILIENCE);
    }
}
