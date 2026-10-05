package com.smd.fulfillmentservice.events;

import java.util.List;
import java.util.UUID;

/** Payloads of fulfillment-events (docs/events.md). */
public final class FulfillmentPayloads {

    public static final String FULFILLMENT_RECEIVED = "FULFILLMENT_RECEIVED";
    public static final String FULFILLMENT_PICKING = "FULFILLMENT_PICKING";
    public static final String FULFILLMENT_PACKED = "FULFILLMENT_PACKED";
    public static final String FULFILLMENT_FAILED = "FULFILLMENT_FAILED";

    private FulfillmentPayloads() {
    }

    public record Received(UUID fulfillmentId, String warehouseCode, List<OrderConfirmed.Line> items) {
    }

    public record Picking(UUID fulfillmentId) {
    }

    public record Packed(UUID fulfillmentId, String warehouseCode, ShippingAddress shippingAddress) {
    }

    public record Failed(UUID fulfillmentId, String reason) {
    }
}
