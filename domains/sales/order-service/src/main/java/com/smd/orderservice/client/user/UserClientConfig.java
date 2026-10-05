package com.smd.orderservice.client.user;

import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/** Feign configuration for {@link UserClient} only. Not a {@code @Configuration}, so it doesn't apply to other clients. */
class UserClientConfig {

    @Bean
    ErrorDecoder userErrorDecoder() {
        return new UserErrorDecoder();
    }
}
