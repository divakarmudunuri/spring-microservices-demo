package com.smd.productservice.api;

import com.smd.productservice.catalog.CatalogQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public catalog. Filters, search, sorting and caching arrive in phase 12 (CLAUDE.md 6.12).
 * Parameter constraints are checked by Spring MVC's built-in method validation (400 ProblemDetail).
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    static final int MAX_BATCH_SIZE = 100;

    private final CatalogQueryService catalog;

    public ProductController(CatalogQueryService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public PageResponse<ProductResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by("name").and(Sort.by("id")));
        return PageResponse.from(catalog.listProducts(pageable), ProductResponse::from);
    }

    /** Batch lookup used by Feign callers: {@code ?ids=a,b,c}. Unknown or inactive ids are left out. */
    @GetMapping(params = "ids")
    public List<ProductResponse> batch(@RequestParam @Size(min = 1, max = MAX_BATCH_SIZE) List<UUID> ids) {
        return catalog.getProducts(ids).stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/{idOrSlug}")
    public ProductResponse get(@PathVariable String idOrSlug) {
        return ProductResponse.from(catalog.getProduct(idOrSlug));
    }
}
