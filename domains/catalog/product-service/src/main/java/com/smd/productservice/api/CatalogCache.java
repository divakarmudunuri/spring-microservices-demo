package com.smd.productservice.api;

import com.smd.productservice.catalog.CatalogQueryService;
import com.smd.productservice.catalog.ProductFilter;
import com.smd.productservice.catalog.ProductSort;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The public catalog, cached (Caffeine, short TTL, bounded size: {@code spring.cache.caffeine.spec}). Cached values
 * are the response records, never JPA entities. Nothing here depends on who is asking, so one cached answer serves
 * everyone. Stock levels change through {@code inventory-events}; {@link #evictAll()} then drops everything.
 */
@Component
public class CatalogCache {

    static final String CATEGORIES = "categories";
    static final String PRODUCT_PAGES = "productPages";
    static final String PRODUCT_BY_ID = "productById";
    static final String FEATURED = "featured";

    private final CatalogQueryService catalog;

    public CatalogCache(CatalogQueryService catalog) {
        this.catalog = catalog;
    }

    @Cacheable(CATEGORIES)
    public List<CategoryResponse> categories() {
        return catalog.listCategories().stream().map(CategoryResponse::from).toList();
    }

    /** The home page's "featured" list is its own cache; every other combination shares {@code productPages}. */
    @Caching(cacheable = {
            @Cacheable(cacheNames = FEATURED, condition = "#filter.featuredOnly()"),
            @Cacheable(cacheNames = PRODUCT_PAGES, condition = "!#filter.featuredOnly()")})
    public PageResponse<ProductResponse> products(ProductFilter filter, ProductSort sort, int page, int size) {
        return PageResponse.from(catalog.listProducts(filter, PageRequest.of(page, size, sort.sort())), ProductResponse::from);
    }

    /** Keyed by what the caller asked for (an id or a slug). */
    @Cacheable(PRODUCT_BY_ID)
    public ProductResponse product(String idOrSlug) {
        return ProductResponse.from(catalog.getProduct(idOrSlug));
    }

    /**
     * After a stock level changed. Everything goes: a level appears in pages, single products and the featured list,
     * and with a 60 s TTL and a small catalog, re-reading is cheap.
     */
    @CacheEvict(cacheNames = {PRODUCT_PAGES, PRODUCT_BY_ID, FEATURED}, allEntries = true)
    public void evictProducts() {
    }

    @CacheEvict(cacheNames = {CATEGORIES, PRODUCT_PAGES, PRODUCT_BY_ID, FEATURED}, allEntries = true)
    public void evictAll() {
    }
}
