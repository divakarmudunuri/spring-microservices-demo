package com.smd.cartservice.cart;

/** Not in the catalog (or inactive). → 404 */
public class UnknownProductException extends RuntimeException {

    public UnknownProductException(String message) {
        super(message);
    }
}
