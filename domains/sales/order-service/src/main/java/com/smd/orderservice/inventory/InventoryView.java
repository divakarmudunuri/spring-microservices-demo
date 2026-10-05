package com.smd.orderservice.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code name} is null when product-service couldn't be reached ({@code namesAvailable} is then false). */
public record InventoryView(List<Item> items, boolean namesAvailable) {

    public record Item(UUID productId, String name, int quantityOnHand, Instant updatedAt) {
    }
}
