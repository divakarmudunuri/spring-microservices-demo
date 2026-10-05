package com.smd.shippingservice.api;

import com.smd.shippingservice.events.ShippingAddress;
import com.smd.shippingservice.shipment.Shipment;
import com.smd.shippingservice.shipment.ShipmentStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ShipmentResponse(UUID id, UUID orderId, String trackingNumber, String carrier, ShipmentStatus status,
                               LocalDate estimatedDelivery, Instant deliveredAt, ShippingAddress shippingAddress) {

    static ShipmentResponse from(Shipment s) {
        return new ShipmentResponse(s.getId(), s.getOrderId(), s.getTrackingNumber(), s.getCarrier(), s.getStatus(),
                s.getEstimatedDelivery(), s.getDeliveredAt(), s.getShippingAddress());
    }
}
