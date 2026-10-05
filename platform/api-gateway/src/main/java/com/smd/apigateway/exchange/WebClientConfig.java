package com.smd.apigateway.exchange;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    /**
     * The gateway's only HTTP client (it is reactive, so no Feign here). {@code @LoadBalanced}: "http://user-service"
     * is resolved through Eureka, like everywhere else.
     */
    @Bean
    @LoadBalanced
    WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }
}
