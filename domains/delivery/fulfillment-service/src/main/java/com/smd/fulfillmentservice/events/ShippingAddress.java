package com.smd.fulfillmentservice.events;

/** The address snapshot from ORDER_CONFIRMED, kept on the fulfillment and passed on in FULFILLMENT_PACKED. */
public record ShippingAddress(String fullName, String line1, String line2, String city, String state,
                              String postalCode, String country, String phone) {
}
