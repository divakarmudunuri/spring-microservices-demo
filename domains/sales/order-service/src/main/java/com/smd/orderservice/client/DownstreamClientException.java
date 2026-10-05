package com.smd.orderservice.client;

/**
 * A downstream service answered 4xx (other than a meaningful 404): our request was wrong, so retrying
 * won't help, and the downstream service isn't unhealthy, so the circuit breaker ignores it.
 */
public class DownstreamClientException extends RuntimeException {

    public DownstreamClientException(String message) {
        super(message);
    }
}
