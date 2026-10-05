package com.smd.productservice.api;

import com.smd.productservice.catalog.Category;
import java.util.UUID;

public record CategoryResponse(UUID id, String slug, String name) {

    static CategoryResponse from(Category c) {
        return new CategoryResponse(c.getId(), c.getSlug(), c.getName());
    }
}
