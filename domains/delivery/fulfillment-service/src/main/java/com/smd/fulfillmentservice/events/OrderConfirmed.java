package com.smd.fulfillmentservice.events;

import java.util.List;
import java.util.UUID;

/** The part of ORDER_CONFIRMED's payload this service needs. Other fields are ignored. */
public record OrderConfirmed(List<Line> items, ShippingAddress shippingAddress) {

    public record Line(UUID productId, int quantity) {
    }
}
