package com.smd.orderservice.composition;

/** Names for the correlation id, shared by the web filter, the MDC and the Feign interceptor. */
public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationIds() {
    }
}
