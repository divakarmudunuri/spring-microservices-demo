package com.smd.cartservice.client.product;

import java.math.BigDecimal;
import java.util.UUID;

/** A product's current name, picture, price and stock level, as the cart shows them. */
public record ProductInfo(UUID id, String name, String slug, String imageUrl, BigDecimal price, String currency,
                          String availability) {

    public boolean outOfStock() {
        return "OUT_OF_STOCK".equals(availability);
    }
}
