package com.smd.orderservice.order;

/** Lifecycle: data-model/01-order_db.md. Only moves forward. */
public enum OrderStatus {
    INITIATED,
    CONFIRMED,
    REJECTED,
    FAILED,
    IN_FULFILLMENT,
    SHIPPED,
    DELIVERED,
    COMPLETED,
    CANCELLED
}
