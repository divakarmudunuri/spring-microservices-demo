package com.smd.cartservice.cart;

/** The cart kept changing under us (two conflicts in a row). → 409 */
public class CartBusyException extends RuntimeException {

    public CartBusyException(String message) {
        super(message);
    }
}
