package com.smd.cartservice.client;

/** product-service couldn't answer (after retries, open circuit, timeout). */
public class ProductsUnavailableException extends RuntimeException {

    public ProductsUnavailableException(Throwable cause) {
        super("product-service is unavailable", cause);
    }
}
