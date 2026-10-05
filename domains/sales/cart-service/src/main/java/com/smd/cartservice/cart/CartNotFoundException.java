package com.smd.cartservice.cart;

/** No such cart (or it isn't yours). → 404 */
public class CartNotFoundException extends RuntimeException {

    public CartNotFoundException(String message) {
        super(message);
    }
}
