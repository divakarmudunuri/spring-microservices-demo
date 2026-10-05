package com.smd.orderservice.client;

import com.smd.orderservice.OrderServiceIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Base for the per-client WireMock tests: the real Feign client + error decoder + Resilience4j
 * annotations, with timeouts and windows shrunk so the tests run fast. (One shared Spring context.)
 */
@TestPropertySource(properties = {
        "spring.cloud.openfeign.client.config.user-service.read-timeout=300",
        "spring.cloud.openfeign.client.config.product-service.read-timeout=300",
        "spring.cloud.openfeign.client.config.shipping-service.read-timeout=300",
        "spring.cloud.openfeign.client.config.order-tracking-service.read-timeout=300",
        "spring.cloud.openfeign.client.config.cart-service.read-timeout=300",
        "resilience4j.retry.configs.default.wait-duration=10ms",
        "resilience4j.circuitbreaker.configs.default.sliding-window-size=4",
        "resilience4j.circuitbreaker.configs.default.minimum-number-of-calls=4",
        "resilience4j.bulkhead.configs.default.max-concurrent-calls=2",
        "resilience4j.bulkhead.configs.default.max-wait-duration=0",
})
public abstract class FeignClientTest extends OrderServiceIntegrationTest {

    @Autowired
    protected CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void noDefaultStubs() {
        DOWNSTREAM.resetAll();   // each test stubs exactly what it needs (circuit breakers are reset by the base class)
    }
}
