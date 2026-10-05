package com.smd.orderservice.client.shipping;

import java.time.Instant;
import java.time.LocalDate;

/** shipping-service's response, as this service needs it. Never leaves this package. */
record ShipmentDto(String trackingNumber, String carrier, String status, LocalDate estimatedDelivery, Instant deliveredAt) {
}
