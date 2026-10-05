package com.smd.cartservice.client;

/** 5xx or 429 from a downstream service: retried, and counted by the circuit breaker. */
public class DownstreamServerException extends RuntimeException {

    public DownstreamServerException(String message) {
        super(message);
    }
}
