package com.smd.orderservice.checkout;

import com.smd.orderservice.order.RejectionReason;
import java.util.UUID;

/** The order was recorded as REJECTED or FAILED. Carries the order id so the client can still track it. */
public class CheckoutRejectedException extends RuntimeException {

    private final UUID orderId;
    private final RejectionReason reason;

    public CheckoutRejectedException(UUID orderId, RejectionReason reason, String detail) {
        super(detail);
        this.orderId = orderId;
        this.reason = reason;
    }

    public UUID orderId() {
        return orderId;
    }

    public RejectionReason reason() {
        return reason;
    }
}
