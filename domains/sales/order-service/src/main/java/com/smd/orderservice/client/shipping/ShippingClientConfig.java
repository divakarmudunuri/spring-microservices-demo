package com.smd.orderservice.client.shipping;

import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/** Feign configuration for {@link ShippingClient} only. Not a {@code @Configuration}. */
class ShippingClientConfig {

    @Bean
    ErrorDecoder shippingErrorDecoder() {
        return new ShippingErrorDecoder();
    }
}
