package com.smd.orderservice.client;

/** A downstream service could not answer (down, 5xx, timeout). Checkout turns this into FAILED / 503. */
public class DependencyUnavailableException extends RuntimeException {

    private final String dependency;

    public DependencyUnavailableException(String dependency, Throwable cause) {
        super(dependency + " is unavailable", cause);
        this.dependency = dependency;
    }

    public String dependency() {
        return dependency;
    }
}
