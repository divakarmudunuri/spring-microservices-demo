package com.smd.orderservice.client.tracking;

import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/** Feign configuration for {@link TrackingClient} only. Not a {@code @Configuration}. */
class TrackingClientConfig {

    @Bean
    ErrorDecoder trackingErrorDecoder() {
        return new TrackingErrorDecoder();
    }
}
