package com.smd.cartservice.persistence;

/** The cart changed since it was read (version condition failed). The service retries once, then answers 409. */
public class CartConflictException extends RuntimeException {

    public CartConflictException(String message) {
        super(message);
    }
}
