package com.smd.orderservice.client.product;

import com.smd.orderservice.client.DownstreamErrors;
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
 * The only way the rest of order-service talks to product-service. Applies resilience and maps DTOs to
 * domain types; see {@link com.smd.orderservice.client.user.UserAdapter} for why the annotations live here.
 */
@Component
public class ProductAdapter {

    static final String RESILIENCE = "productService";

    private final ProductClient client;

    public ProductAdapter(ProductClient client) {
        this.client = client;
    }

    /**
     * Active products by id. Ids that are unknown or inactive are missing from the map.
     *
     * @throws com.smd.orderservice.client.DependencyUnavailableException product-service couldn't answer
     */
    @Retry(name = RESILIENCE, fallbackMethod = "translateFailure")
    @CircuitBreaker(name = RESILIENCE)
    @Bulkhead(name = RESILIENCE)
    public Map<UUID, ProductInfo> getProducts(Collection<UUID> ids) {
        return client.getProducts(ids).stream()
                .map(p -> new ProductInfo(p.id(), p.name(), p.description(), p.price(), p.currency()))
                .collect(Collectors.toMap(ProductInfo::id, Function.identity()));
    }

    // called by Resilience4j after the last attempt failed; no fallback value, see DownstreamErrors.translate
    private Map<UUID, ProductInfo> translateFailure(Collection<UUID> ids, Throwable failure) {
        throw DownstreamErrors.translate("product-service", failure);
    }
}
