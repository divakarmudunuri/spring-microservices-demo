package com.smd.orderservice.order;

/** Why an order was REJECTED (business rule) or FAILED ({@link #DEPENDENCY_UNAVAILABLE}). */
public enum RejectionReason {
    OUT_OF_STOCK,
    INSUFFICIENT_FUNDS,
    USER_INACTIVE,
    PRODUCT_NOT_FOUND,
    EMPTY_CART,
    NO_SHIPPING_ADDRESS,
    DEPENDENCY_UNAVAILABLE
}
