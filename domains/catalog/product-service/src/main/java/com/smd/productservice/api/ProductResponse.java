package com.smd.productservice.api;

import com.smd.productservice.catalog.AvailabilityLevel;
import com.smd.productservice.catalog.ProductView;
import java.math.BigDecimal;
import java.util.UUID;

/** Public product data. Carries an availability level, never an exact stock count. */
public record ProductResponse(
        UUID id,
        String sku,
        String slug,
        String name,
        String description,
        CategoryResponse category,
        String imageUrl,
        BigDecimal price,
        String currency,
        boolean featured,
        AvailabilityLevel availability) {

    static ProductResponse from(ProductView view) {
        var p = view.product();
        return new ProductResponse(p.getId(), p.getSku(), p.getSlug(), p.getName(), p.getDescription(),
                CategoryResponse.from(p.getCategory()), p.getImageUrl(), p.getPrice(), p.getCurrency(),
                p.isFeatured(), view.availability());
    }
}
