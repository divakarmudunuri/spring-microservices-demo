package com.smd.orderservice.inventory;

import java.util.UUID;

/** No inventory row for this product. → 404 */
public class UnknownProductException extends RuntimeException {

    public UnknownProductException(UUID productId) {
        super("No inventory for product " + productId);
    }
}
