package com.smd.orderservice.client.shipping;

import com.smd.orderservice.client.DownstreamErrors;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The only way order-service talks to shipping-service. Retry( CircuitBreaker( Bulkhead( call ))). */
@Component
public class ShippingAdapter {

    static final String RESILIENCE = "shippingService";

    private final ShippingClient client;

    public ShippingAdapter(ShippingClient client) {
        this.client = client;
    }

    /**
     * @return empty if the order has no shipment yet (404: not an error, and not retried or counted)
     * @throws com.smd.orderservice.client.DependencyUnavailableException shipping-service couldn't answer
     */
    @Retry(name = RESILIENCE, fallbackMethod = "translateFailure")
    @CircuitBreaker(name = RESILIENCE)
    @Bulkhead(name = RESILIENCE)
    public Optional<Shipment> findShipment(UUID orderId) {
        try {
            ShipmentDto s = client.getShipment(orderId);
            return Optional.of(new Shipment(s.trackingNumber(), s.carrier(), s.status(), s.estimatedDelivery(), s.deliveredAt()));
        } catch (ShipmentNotFoundException e) {
            return Optional.empty();
        }
    }

    // no fallback value here; the aggregator decides what an unavailable section looks like
    private Optional<Shipment> translateFailure(UUID orderId, Throwable failure) {
        throw DownstreamErrors.translate("shipping-service", failure);
    }
}
