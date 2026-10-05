package com.smd.storefrontbff.client.product;

/** 5xx or 429 from product-service: retried, and counted by the circuit breaker. */
public class DownstreamServerException extends RuntimeException {

    public DownstreamServerException(String message) {
        super(message);
    }
}
