package com.smd.storefrontbff.storefront;

import java.util.List;

/** What the storefront's pages get: everything for one screen in one response. Sections can be unavailable. */
public final class StorefrontPages {

    private StorefrontPages() {
    }

    /** A section is an empty list when it is unavailable; {@code unavailableSections} says which. */
    public record Home(List<Catalog.Category> categories, List<Catalog.Product> featured,
                       List<Catalog.Product> newArrivals, boolean degraded, List<String> unavailableSections) {
    }

    public record ProductPage(Catalog.Product product, List<Catalog.Product> related, List<Catalog.Category> categories,
                              boolean degraded, List<String> unavailableSections) {
    }
}
