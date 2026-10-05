package com.smd.apigateway.exchange;

import org.springframework.http.HttpStatus;

/** The token exchange didn't produce an internal token: 401 (bad token), 403 (refused), 503 (user-service down). */
public class ExchangeFailedException extends RuntimeException {

    private final HttpStatus status;

    public ExchangeFailedException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
