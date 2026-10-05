package com.smd.orderservice.client.cart;

import com.smd.orderservice.checkout.OrderLine;
import java.util.List;
import java.util.UUID;

/** The customer's cart at checkout time: which version, and what's in it. */
public record Cart(UUID cartId, long version, List<OrderLine> lines) {
}
