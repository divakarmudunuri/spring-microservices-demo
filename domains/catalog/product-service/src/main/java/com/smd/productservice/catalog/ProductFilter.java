package com.smd.productservice.catalog;

import org.springframework.util.StringUtils;

/**
 * Optional filters of the public product list.
 *
 * @param category a category slug
 * @param q        case-insensitive match on name or description
 * @param featured only featured products
 */
public record ProductFilter(String category, String q, Boolean featured) {

    /** Just "the featured products": the home page's question, cached separately. */
    public boolean featuredOnly() {
        return Boolean.TRUE.equals(featured) && !StringUtils.hasText(category) && !StringUtils.hasText(q);
    }
}
