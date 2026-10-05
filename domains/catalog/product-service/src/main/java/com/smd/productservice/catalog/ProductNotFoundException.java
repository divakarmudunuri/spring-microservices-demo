package com.smd.productservice.catalog;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String idOrSlug) {
        super("Product " + idOrSlug + " not found");
    }
}
