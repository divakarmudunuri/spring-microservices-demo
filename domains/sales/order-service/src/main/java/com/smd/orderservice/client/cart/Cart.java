package com.smd.orderservice.client.cart;

import com.smd.orderservice.checkout.OrderLine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The customer's cart at checkout time: which version, and what's in it. {@code createdAt} tells two lives of the
 * same (user-derived) cart id apart; it may be null for a cart written before cart-service returned it.
 */
public record Cart(UUID cartId, long version, Instant createdAt, List<OrderLine> lines) {
}
