package com.smd.cartservice.client.product;

import com.smd.cartservice.observability.CorrelationIdFilter;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;

/** Feign configuration for {@link ProductClient} only. Not a {@code @Configuration}. */
class ProductClientConfig {

    @Bean
    ErrorDecoder productErrorDecoder() {
        return new ProductErrorDecoder();
    }

    /**
     * Passes the correlation id on, so product-service's logs for this call carry the same id. (The trace context
     * travels on its own: feign-micrometer adds the W3C traceparent header.) Catalog calls are anonymous: no token.
     */
    @Bean
    RequestInterceptor correlationIdForwarder() {
        return template -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                template.header(CorrelationIdFilter.HEADER, correlationId);
            }
        };
    }
}
