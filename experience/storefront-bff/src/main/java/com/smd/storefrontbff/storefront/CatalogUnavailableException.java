package com.smd.storefrontbff.storefront;

/** A product page's product couldn't be loaded (product-service down, timeout, open circuit). → 503 */
public class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(Throwable cause) {
        super("The catalog is not available right now", cause);
    }
}
