package com.smd.cartservice.client.product;

import java.math.BigDecimal;
import java.util.UUID;

/** product-service's response, as this service needs it. Never leaves this package. */
record ProductDto(UUID id, String name, String slug, String imageUrl, BigDecimal price, String currency, String availability) {
}
