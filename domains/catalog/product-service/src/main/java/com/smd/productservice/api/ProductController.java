package com.smd.productservice.api;

import com.smd.productservice.catalog.CatalogQueryService;
import com.smd.productservice.catalog.ProductFilter;
import com.smd.productservice.catalog.ProductSort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public catalog (CLAUDE.md 6.12): anyone may browse. Every product carries an availability level, never an
 * exact stock count. Parameter constraints are checked by Spring MVC's built-in method validation (400 ProblemDetail).
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    static final int MAX_BATCH_SIZE = 100;

    private final CatalogCache cache;
    private final CatalogQueryService catalog;

    public ProductController(CatalogCache cache, CatalogQueryService catalog) {
        this.cache = cache;
        this.catalog = catalog;
    }

    /** Paged and filtered: {@code ?category=<slug>&q=<text>&featured=true&sort=name|price-asc|price-desc|newest}. */
    @GetMapping
    public PageResponse<ProductResponse> list(
            @RequestParam(required = false) @Size(max = 80) String category,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) @Pattern(regexp = "(?i)name|price-asc|price-desc|newest") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return cache.products(new ProductFilter(category, q, featured), ProductSort.fromParameter(sort), page, size);
    }

    /**
     * Batch lookup used by Feign callers (checkout prices, cart view, aggregator): {@code ?ids=a,b,c}. Unknown or
     * inactive ids are left out. Not cached: callers want the current price and level.
     */
    @GetMapping(params = "ids")
    public List<ProductResponse> batch(@RequestParam @Size(min = 1, max = MAX_BATCH_SIZE) List<UUID> ids) {
        return catalog.getProducts(ids).stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/{idOrSlug}")
    public ProductResponse get(@PathVariable String idOrSlug) {
        return cache.product(idOrSlug);
    }
}
