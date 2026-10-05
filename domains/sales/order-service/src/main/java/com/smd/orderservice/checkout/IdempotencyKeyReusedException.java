package com.smd.orderservice.checkout;

/** The Idempotency-Key already belongs to another customer's order. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key has already been used");
    }
}
