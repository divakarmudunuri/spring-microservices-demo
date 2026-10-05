package com.smd.ordertrackingservice.tracking;

import java.util.Map;
import java.util.Optional;

/**
 * How far along an order is, per event type (data-model/05-dynamodb.md). The STATE item only moves to a
 * higher rank, so late or out-of-order events (different topics, different partitions) can't move the
 * status backwards. Gaps of 5–10 leave room for new event types.
 */
public final class StatusRanks {

    private static final Map<String, Integer> RANKS = Map.ofEntries(
            Map.entry("ORDER_INITIATED", 10),
            Map.entry("INVENTORY_RESERVED", 20),
            Map.entry("PAYMENT_CAPTURED", 25),
            // the three outcomes of checkout share a rank: only one of them ever happens
            Map.entry("ORDER_CONFIRMED", 30),
            Map.entry("ORDER_REJECTED", 30),
            Map.entry("ORDER_FAILED", 30),
            Map.entry("FULFILLMENT_RECEIVED", 40),
            Map.entry("FULFILLMENT_PICKING", 45),
            Map.entry("FULFILLMENT_PACKED", 50),
            Map.entry("SHIPMENT_CREATED", 55),
            Map.entry("SHIPMENT_PICKED_UP", 60),
            Map.entry("SHIPMENT_IN_TRANSIT", 70),
            Map.entry("SHIPMENT_OUT_FOR_DELIVERY", 75),
            Map.entry("SHIPMENT_DELIVERED", 80),
            Map.entry("ORDER_DELIVERED", 85),
            // compensation (fulfillment failed after payment): ends in ORDER_CANCELLED
            Map.entry("FULFILLMENT_FAILED", 90),
            Map.entry("INVENTORY_RESTORED", 91),
            Map.entry("PAYMENT_REFUNDED", 92),
            Map.entry("ORDER_CANCELLED", 95),
            // the customer confirmed delivery: COMPLETED, the highest rank
            Map.entry("DELIVERY_ACKNOWLEDGED", 100));

    private StatusRanks() {
    }

    /** Empty for event types this service doesn't know (they are logged and skipped). */
    public static Optional<Integer> rankOf(String eventType) {
        return Optional.ofNullable(RANKS.get(eventType));
    }
}
