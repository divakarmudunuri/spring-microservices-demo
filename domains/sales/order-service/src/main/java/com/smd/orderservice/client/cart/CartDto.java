package com.smd.orderservice.client.cart;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** cart-service's view, as checkout needs it (ids and quantities; prices come from product-service). */
record CartDto(UUID cartId, long version, Instant createdAt, List<Line> lines) {

    record Line(UUID productId, int quantity) {
    }
}
