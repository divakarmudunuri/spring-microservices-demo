package com.smd.orderservice.client;

import feign.Response;

/** Shared pieces of the per-client error decoders and adapters. */
public final class DownstreamErrors {

    private DownstreamErrors() {
    }

    /** 5xx and 429 → retryable; any other 4xx → not retryable. 404 is handled by each decoder first. */
    public static RuntimeException byStatus(String dependency, Response response) {
        String message = dependency + " answered " + response.status() + " for "
                + response.request().httpMethod() + " " + response.request().url();
        if (response.status() >= 500 || response.status() == 429) {
            return new DownstreamServerException(message);
        }
        return new DownstreamClientException(message);
    }

    /**
     * Used by the adapters' fallback methods. Checkout lookups have <b>no fallback value</b>: they must fail.
     * This only translates the infrastructure exceptions (5xx after retries, timeouts, open circuit, full
     * bulkhead, unexpected 4xx) into one {@link DependencyUnavailableException}, so callers don't depend on
     * Feign or Resilience4j types. Domain exceptions such as "not found" pass through unchanged.
     */
    public static RuntimeException translate(String dependency, Throwable failure, Class<?>... passThrough) {
        for (Class<?> type : passThrough) {
            if (type.isInstance(failure)) {
                return (RuntimeException) failure;
            }
        }
        return new DependencyUnavailableException(dependency, failure);
    }
}
