package com.smd.storefrontbff.client.product;

/** No product with that slug (404 from product-service): not retried, not a failure. */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String message) {
        super(message);
    }
}
