package com.smd.orderservice.checkout;

import java.util.UUID;

/** A request with the same Idempotency-Key is still being processed. */
public class OrderInProgressException extends RuntimeException {

    private final UUID orderId;

    public OrderInProgressException(UUID orderId) {
        super("Order " + orderId + " with this Idempotency-Key is still being processed");
        this.orderId = orderId;
    }

    public UUID orderId() {
        return orderId;
    }
}
