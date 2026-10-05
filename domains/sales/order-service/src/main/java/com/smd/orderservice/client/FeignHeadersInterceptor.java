package com.smd.orderservice.client;

import com.smd.orderservice.composition.CorrelationIds;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Adds cross-cutting headers to every Feign call. Tracing headers (traceparent) are added
 * separately by feign-micrometer.
 */
@Component
public class FeignHeadersInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // read from the MDC rather than the HTTP request: this also works on compose- worker threads
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        if (correlationId != null) {
            template.header(CorrelationIds.HEADER, correlationId);
        }
        // TODO(phase-11): relay the caller's JWT (Authorization: Bearer ...) from the SecurityContext
    }
}
