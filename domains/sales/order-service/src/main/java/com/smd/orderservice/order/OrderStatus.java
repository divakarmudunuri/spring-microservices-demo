package com.smd.orderservice.order;

/** Lifecycle: data-model/01-order_db.md. Only moves forward. */
public enum OrderStatus {
    INITIATED(0),
    CONFIRMED(1),
    REJECTED(0),
    FAILED(0),
    IN_FULFILLMENT(2),
    SHIPPED(3),
    DELIVERED(4),
    COMPLETED(5),
    CANCELLED(0);

    /** Position on the delivery path; 0 = not on it (not confirmed yet, or ended without delivery). */
    private final int progress;

    OrderStatus(int progress) {
        this.progress = progress;
    }

    /** True if moving from this status to {@code target} is a step forward on the delivery path. */
    public boolean canAdvanceTo(OrderStatus target) {
        return progress > 0 && target.progress > progress;
    }
}
