package com.smd.orderservice.order;

/** The address snapshot stored on the order at checkout (JSONB), so later address edits don't change it. */
public record ShippingAddress(
        String fullName,
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country,
        String phone) {
}
