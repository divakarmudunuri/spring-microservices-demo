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

    private EventTypes() {
    }
}
