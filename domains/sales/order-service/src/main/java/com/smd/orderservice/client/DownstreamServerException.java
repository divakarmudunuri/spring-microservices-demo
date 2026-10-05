package com.smd.orderservice.client;

/**
 * A downstream service answered 5xx or 429: worth retrying, and counted as a failure by the
 * circuit breaker. (I/O errors and timeouts arrive as {@code feign.RetryableException} and are treated the same.)
 */
public class DownstreamServerException extends RuntimeException {

    public DownstreamServerException(String message) {
        super(message);
    }
}
