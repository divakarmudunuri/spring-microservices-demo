package com.smd.orderservice.client.cart;

import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/** Feign configuration for {@link CartClient} only. Not a {@code @Configuration}. */
class CartClientConfig {

    @Bean
    ErrorDecoder cartErrorDecoder() {
        return new CartErrorDecoder();
    }
}
