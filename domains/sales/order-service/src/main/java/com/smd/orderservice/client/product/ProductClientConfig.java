package com.smd.orderservice.client.product;

import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/** Feign configuration for {@link ProductClient} only. Not a {@code @Configuration}, so it doesn't apply to other clients. */
class ProductClientConfig {

    @Bean
    ErrorDecoder productErrorDecoder() {
        return new ProductErrorDecoder();
    }
}
