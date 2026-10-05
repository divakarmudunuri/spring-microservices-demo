package com.smd.shippingservice.events;

/** The address from FULFILLMENT_PACKED, stored on the shipment (JSONB). */
public record ShippingAddress(String fullName, String line1, String line2, String city, String state,
                              String postalCode, String country, String phone) {
}
