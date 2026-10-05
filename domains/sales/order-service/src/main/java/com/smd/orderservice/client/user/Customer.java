package com.smd.orderservice.client.user;

import com.smd.orderservice.order.ShippingAddress;
import java.util.Optional;
import java.util.UUID;

/** What order-service knows about a customer, mapped from user-service's response. */
public record Customer(UUID id, String fullName, String email, boolean active,
                       Optional<ShippingAddress> defaultAddress) {
}
