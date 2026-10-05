package com.smd.cartservice.client;

/** Any other 4xx: our request was wrong; not retried, ignored by the circuit breaker. */
public class DownstreamClientException extends RuntimeException {

    public DownstreamClientException(String message) {
        super(message);
    }
}
