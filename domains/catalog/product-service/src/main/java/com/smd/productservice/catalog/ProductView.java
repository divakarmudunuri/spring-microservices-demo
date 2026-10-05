package com.smd.productservice.catalog;

/** A product together with its public availability level. */
public record ProductView(Product product, AvailabilityLevel availability) {
}
