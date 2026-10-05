package com.smd.cartservice.cart;

/** Too many lines or too many of one product. → 422 */
public class CartLimitException extends RuntimeException {

    public CartLimitException(String message) {
        super(message);
    }
}
