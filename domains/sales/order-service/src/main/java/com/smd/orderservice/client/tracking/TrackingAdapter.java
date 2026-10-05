package com.smd.orderservice.client.tracking;

import com.smd.orderservice.client.DownstreamErrors;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The only way order-service talks to order-tracking-service. Retry( CircuitBreaker( Bulkhead( call ))). */
@Component
public class TrackingAdapter {

    static final String RESILIENCE = "trackingService";

    private final TrackingClient client;

    public TrackingAdapter(TrackingClient client) {
        this.client = client;
    }

    /**
     * @return empty if tracking hasn't recorded anything for this order yet (404)
     * @throws com.smd.orderservice.client.DependencyUnavailableException order-tracking-service couldn't answer
     */
    @Retry(name = RESILIENCE, fallbackMethod = "translateFailure")
    @CircuitBreaker(name = RESILIENCE)
    @Bulkhead(name = RESILIENCE)
    public Optional<Tracking> findTracking(UUID orderId) {
        try {
            TrackingDto t = client.getTracking(orderId);
            return Optional.of(new Tracking(t.currentStatus(), t.timeline().stream()
                    .map(e -> new Tracking.Entry(e.status(), e.source(), e.occurredAt(), e.details()))
                    .toList()));
        } catch (TrackingNotFoundException e) {
            return Optional.empty();
        }
    }

    // no fallback value here; the aggregator decides what an unavailable section looks like
    private Optional<Tracking> translateFailure(UUID orderId, Throwable failure) {
        throw DownstreamErrors.translate("order-tracking-service", failure);
    }
}
