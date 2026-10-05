package com.smd.orderservice.client.user;

/** user-service answered 404. Not retried, ignored by the circuit breaker. */
public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(String message) {
        super(message);
    }
}
