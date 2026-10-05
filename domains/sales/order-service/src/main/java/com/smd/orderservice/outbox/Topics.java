package com.smd.orderservice.outbox;

/** Kafka topics this service produces to (docs/events.md). */
public final class Topics {

    public static final String ORDER_EVENTS = "order-events";
    public static final String INVENTORY_EVENTS = "inventory-events";

    private Topics() {
    }
}
