package com.smd.shippingservice.shipment;

import com.smd.shippingservice.events.ShippingPayloads;

/** LABEL_CREATED → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED, each with its event type. */
public enum ShipmentStatus {
    LABEL_CREATED(ShippingPayloads.SHIPMENT_CREATED),
    PICKED_UP(ShippingPayloads.SHIPMENT_PICKED_UP),
    IN_TRANSIT(ShippingPayloads.SHIPMENT_IN_TRANSIT),
    OUT_FOR_DELIVERY(ShippingPayloads.SHIPMENT_OUT_FOR_DELIVERY),
    DELIVERED(ShippingPayloads.SHIPMENT_DELIVERED);

    private final String eventType;

    ShipmentStatus(String eventType) {
        this.eventType = eventType;
    }

    public String eventType() {
        return eventType;
    }

    ShipmentStatus next() {
        return values()[ordinal() + 1];
    }
}
