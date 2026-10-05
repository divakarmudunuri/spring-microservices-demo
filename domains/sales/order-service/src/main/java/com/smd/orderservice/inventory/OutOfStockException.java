package com.smd.orderservice.inventory;

import java.util.UUID;

/** Unchecked on purpose: thrown inside the checkout transaction to roll all of it back. */
public class OutOfStockException extends RuntimeException {

    private final UUID productId;

    public OutOfStockException(UUID productId) {
        super("Not enough stock for product " + productId);
        this.productId = productId;
    }

    public UUID productId() {
        return productId;
    }
}
