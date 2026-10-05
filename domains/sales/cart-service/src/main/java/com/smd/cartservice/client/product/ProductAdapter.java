package com.smd.cartservice.client.product;

import com.smd.cartservice.client.ProductsUnavailableException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The only way cart-service talks to product-service: Retry( CircuitBreaker( Bulkhead( call ))), annotations here and
 * not on the Feign interface ({@code spring.cloud.openfeign.circuitbreaker.enabled=false}), so each call is wrapped
 * once. No fallback value: the caller decides (the cart view degrades, adding an item fails with 503).
 */
@Component
public class ProductAdapter {

    static final String RESILIENCE = "productService";

    private final ProductClient client;

    public ProductAdapter(ProductClient client) {
        this.client = client;
    }

    /** Active products by id; unknown or inactive ids are missing. @throws ProductsUnavailableException */
    @Retry(name = RESILIENCE, fallbackMethod = "unavailable")
    @CircuitBreaker(name = RESILIENCE)
    @Bulkhead(name = RESILIENCE)
    public Map<UUID, ProductInfo> getProducts(Collection<UUID> ids) {
        return client.getProducts(ids).stream()
                .map(p -> new ProductInfo(p.id(), p.name(), p.slug(), p.imageUrl(), p.price(), p.currency(), p.availability()))
                .collect(Collectors.toMap(ProductInfo::id, Function.identity()));
    }

    // called by Resilience4j after the last attempt failed: one exception type for callers, no fallback value
    private Map<UUID, ProductInfo> unavailable(Collection<UUID> ids, Throwable failure) {
        throw new ProductsUnavailableException(failure);
    }
}
