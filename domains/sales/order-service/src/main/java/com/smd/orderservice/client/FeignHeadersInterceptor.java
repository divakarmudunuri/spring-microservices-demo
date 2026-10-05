package com.smd.orderservice.client;

import com.smd.orderservice.composition.CorrelationIds;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Adds cross-cutting headers to every Feign call. Tracing headers (traceparent) are added separately by
 * feign-micrometer.
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
        // Relay the caller's own internal JWT, so the downstream service checks the same person's rights
        // (e.g. user-service: owner or ADMIN). With no authenticated caller (Kafka listeners, schedulers),
        // no token is sent. A token is never invented or forged here.
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        if (caller instanceof JwtAuthenticationToken jwt) {
            template.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getToken().getTokenValue());
        }
    }
}
