package com.smd.orderservice.client.tracking;

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
class TrackingAdapterTest extends FeignClientTest {

    static final UUID ORDER = UUID.fromString("40000000-0000-4000-8000-000000000001");
    static final String PATH = "/api/tracking/orders/" + ORDER;
    static final String TRACKING = """
            {"orderId":"%s","currentStatus":"SHIPMENT_IN_TRANSIT","lastEventAt":"2026-10-05T10:00:10.000Z",
             "timeline":[{"status":"ORDER_CONFIRMED","source":"order-service","occurredAt":"2026-10-05T10:00:00.000Z","details":{}},
                         {"status":"SHIPMENT_IN_TRANSIT","source":"shipping-service","occurredAt":"2026-10-05T10:00:10.000Z",
                          "details":{"trackingNumber":"SMDABC"}}]}""".formatted(ORDER);

    @Autowired
    TrackingAdapter tracking;

    @Test
    void successIsMapped() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(TRACKING)));

        assertThat(tracking.findTracking(ORDER)).get()
                .satisfies(t -> {
                    assertThat(t.currentStatus()).isEqualTo("SHIPMENT_IN_TRANSIT");
                    assertThat(t.timeline()).extracting(Tracking.Entry::status).containsExactly("ORDER_CONFIRMED", "SHIPMENT_IN_TRANSIT");
                    assertThat(t.timeline().get(1).details()).containsEntry("trackingNumber", "SMDABC");
                });
    }

    @Test
    void notFoundMeansNoTrackingYetAndIsNotAFailure() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(tracking.findTracking(ORDER)).isEmpty();
        DOWNSTREAM.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void serverErrorIsRetried() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("ok"));
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("ok").willReturn(okJson(TRACKING)));

        assertThat(tracking.findTracking(ORDER)).isPresent();
        DOWNSTREAM.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void slowResponseTimesOutAfterThreeAttempts() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(TRACKING).withFixedDelay(1_000)));

        assertThatThrownBy(() -> tracking.findTracking(ORDER)).isInstanceOf(DependencyUnavailableException.class);
        DOWNSTREAM.verify(3, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> tracking.findTracking(ORDER)).isInstanceOf(DependencyUnavailableException.class);
        }
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> tracking.findTracking(ORDER)).hasCauseInstanceOf(CallNotPermittedException.class);
        DOWNSTREAM.verify(4, getRequestedFor(urlPathEqualTo(PATH)));
    }

    private CircuitBreaker breaker() {
        return circuitBreakers.circuitBreaker(TrackingAdapter.RESILIENCE);
    }
}
