package com.smd.orderservice.client.product;

import com.smd.orderservice.client.DependencyUnavailableException;
import feign.FeignException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** The only way the rest of order-service talks to product-service. Maps its DTOs to domain types. */
@Component
public class ProductAdapter {

    private final ProductClient client;

    public ProductAdapter(ProductClient client) {
        this.client = client;
    }

    /** Active products by id. Ids that are unknown or inactive are missing from the map. */
    // TODO(phase-5): Resilience4j (@Retry, @CircuitBreaker, @Bulkhead) and an ErrorDecoder replace the catch block
    public Map<UUID, ProductInfo> getProducts(Collection<UUID> ids) {
        try {
            return client.getProducts(ids).stream()
                    .map(p -> new ProductInfo(p.id(), p.name(), p.description(), p.price(), p.currency()))
                    .collect(Collectors.toMap(ProductInfo::id, Function.identity()));
        } catch (FeignException e) {
            throw new DependencyUnavailableException("product-service", e);
        }
    }
}
