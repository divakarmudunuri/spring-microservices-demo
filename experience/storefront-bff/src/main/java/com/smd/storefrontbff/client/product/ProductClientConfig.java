package com.smd.storefrontbff.client.product;

import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/** Feign configuration for {@link ProductClient} only. Not a {@code @Configuration}. */
class ProductClientConfig {

    @Bean
    ErrorDecoder productErrorDecoder() {
        return new ProductErrorDecoder();
    }
}
