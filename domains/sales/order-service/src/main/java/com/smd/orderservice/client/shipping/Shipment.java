package com.smd.orderservice.client.shipping;

import java.time.Instant;
import java.time.LocalDate;

/** A shipment as the order-details view shows it. */
public record Shipment(String trackingNumber, String carrier, String status, LocalDate estimatedDelivery,
                       Instant deliveredAt) {
}
