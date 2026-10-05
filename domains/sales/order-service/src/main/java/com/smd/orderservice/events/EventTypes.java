package com.smd.orderservice.events;

/** Event types this service publishes (docs/events.md). */
public final class EventTypes {

    public static final String ORDER_INITIATED = "ORDER_INITIATED";
    public static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";
    public static final String PAYMENT_CAPTURED = "PAYMENT_CAPTURED";
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String ORDER_REJECTED = "ORDER_REJECTED";
    public static final String ORDER_FAILED = "ORDER_FAILED";
    public static final String INVENTORY_CHANGED = "INVENTORY_CHANGED";
    public static final String ORDER_DELIVERED = "ORDER_DELIVERED";
    public static final String INVENTORY_RESTORED = "INVENTORY_RESTORED";
    public static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String DELIVERY_ACKNOWLEDGED = "DELIVERY_ACKNOWLEDGED";

    private EventTypes() {
    }
}
