package com.smd.cartservice.cart;

/** Out of stock: adding it is refused. → 409 */
public class ProductUnavailableException extends RuntimeException {

    public ProductUnavailableException(String message) {
        super(message);
    }
}
