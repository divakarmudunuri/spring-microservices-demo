package com.smd.orderservice.checkout;

/** The request itself is malformed (e.g. the same product twice); nothing is recorded. */
public class InvalidOrderException extends RuntimeException {

    public InvalidOrderException(String message) {
        super(message);
    }
}
