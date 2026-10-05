package com.smd.shippingservice.events;

import java.util.UUID;

/** The part of FULFILLMENT_PACKED's payload this service needs. */
public record FulfillmentPacked(UUID fulfillmentId, String warehouseCode, ShippingAddress shippingAddress) {
}
