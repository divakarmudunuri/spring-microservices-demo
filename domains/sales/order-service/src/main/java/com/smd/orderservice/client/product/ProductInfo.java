package com.smd.orderservice.client.product;

import java.math.BigDecimal;
import java.util.UUID;

/** A product's current name and price, as checkout and the aggregator need them. */
public record ProductInfo(UUID id, String name, String description, BigDecimal price, String currency) {
}
