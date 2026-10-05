package com.smd.storefrontbff.client.product;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** product-service's responses, as this service needs them. Never leave this package. */
record ProductDto(UUID id, String slug, String name, String description, CategoryDto category, String imageUrl,
                  BigDecimal price, String currency, boolean featured, String availability) {
}

record CategoryDto(UUID id, String slug, String name) {
}

record ProductPageDto(List<ProductDto> content) {
}
