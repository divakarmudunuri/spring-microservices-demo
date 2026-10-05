package com.smd.shippingservice.events;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Payloads of shipping-events (docs/events.md). */
public final class ShippingPayloads {

    public static final String SHIPMENT_CREATED = "SHIPMENT_CREATED";
    public static final String SHIPMENT_PICKED_UP = "SHIPMENT_PICKED_UP";
    public static final String SHIPMENT_IN_TRANSIT = "SHIPMENT_IN_TRANSIT";
    public static final String SHIPMENT_OUT_FOR_DELIVERY = "SHIPMENT_OUT_FOR_DELIVERY";
    public static final String SHIPMENT_DELIVERED = "SHIPMENT_DELIVERED";

    private ShippingPayloads() {
    }

    public record Created(UUID shipmentId, String trackingNumber, String carrier, LocalDate estimatedDelivery) {
    }

    /** PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY. */
    public record Progress(UUID shipmentId, String trackingNumber) {
    }

    public record Delivered(UUID shipmentId, String trackingNumber, Instant deliveredAt) {
    }
}
