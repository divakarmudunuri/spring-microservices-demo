package com.smd.productservice.catalog;

import org.springframework.data.domain.Sort;

/** The sort orders the storefront offers ({@code sort=name|price-asc|price-desc|newest}). */
public enum ProductSort {
    NAME(Sort.by("name")),
    PRICE_ASC(Sort.by("price")),
    PRICE_DESC(Sort.by(Sort.Direction.DESC, "price")),
    NEWEST(Sort.by(Sort.Direction.DESC, "createdAt"));

    private final Sort sort;

    ProductSort(Sort sort) {
        this.sort = sort;
    }

    /** Ties broken by id, so paging is stable. */
    public Sort sort() {
        return sort.and(Sort.by("id"));
    }

    public static ProductSort fromParameter(String value) {
        return value == null || value.isBlank() ? NAME : valueOf(value.trim().toUpperCase().replace('-', '_'));
    }
}
