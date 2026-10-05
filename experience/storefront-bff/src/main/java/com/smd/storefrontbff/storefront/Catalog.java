package com.smd.storefrontbff.storefront;

import java.math.BigDecimal;
import java.util.UUID;

/** The storefront's own view of catalog data (mapped from product-service's DTOs by the adapter). */
public final class Catalog {

    private Catalog() {
    }

    public record Category(String slug, String name) {
    }

    /** A product card / page: an availability level, never a stock count. */
    public record Product(UUID id, String slug, String name, String description, Category category, String imageUrl,
                          BigDecimal price, String currency, boolean featured, String availability) {
    }
}
