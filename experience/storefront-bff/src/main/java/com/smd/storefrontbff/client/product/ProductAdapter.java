package com.smd.storefrontbff.client.product;

import com.smd.storefrontbff.storefront.Catalog;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The only way the BFF talks to product-service: Retry( CircuitBreaker( Bulkhead( call ))). No fallbacks here: the
 * page composer decides what a missing section looks like.
 */
@Component
public class ProductAdapter {

    static final String RESILIENCE = "productService";

    private final ProductClient client;

    public ProductAdapter(ProductClient client) {
        this.client = client;
    }

    @Retry(name = RESILIENCE)
    @CircuitBreaker(name = RESILIENCE)
    @Bulkhead(name = RESILIENCE)
    public List<Catalog.Category> categories() {
        return client.categories().stream().map(ProductAdapter::toCategory).toList();
    }

    /** A page of the public list: {@code featured} and {@code category} optional, {@code sort} as product-service names it. */
    @Retry(name = RESILIENCE)
    @CircuitBreaker(name = RESILIENCE)
    @Bulkhead(name = RESILIENCE)
    public List<Catalog.Product> products(String category, Boolean featured, String sort, int size) {
        return client.products(category, featured, sort, size).content().stream().map(ProductAdapter::toProduct).toList();
    }

    /** @throws ProductNotFoundException no such product */
    @Retry(name = RESILIENCE)
    @CircuitBreaker(name = RESILIENCE)
    @Bulkhead(name = RESILIENCE)
    public Catalog.Product product(String slug) {
        return toProduct(client.product(slug));
    }

    private static Catalog.Category toCategory(CategoryDto c) {
        return c == null ? null : new Catalog.Category(c.slug(), c.name());
    }

    private static Catalog.Product toProduct(ProductDto p) {
        return new Catalog.Product(p.id(), p.slug(), p.name(), p.description(), toCategory(p.category()), p.imageUrl(),
                p.price(), p.currency(), p.featured(), p.availability());
    }
}
