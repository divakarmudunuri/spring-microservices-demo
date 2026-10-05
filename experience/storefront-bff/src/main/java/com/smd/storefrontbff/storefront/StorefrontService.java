package com.smd.storefrontbff.storefront;

import com.smd.storefrontbff.client.product.ProductAdapter;
import com.smd.storefrontbff.client.product.ProductNotFoundException;
import com.smd.storefrontbff.composition.ParallelCalls;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The backend-for-frontend's whole job: shape data for the storefront's screens (CLAUDE.md 6.12). No database, no
 * business rules. The gateway routes and secures; this composes, for one client.
 *
 * <p>Independent calls run in parallel on the bounded {@code compositionExecutor} ({@link ParallelCalls}, the same
 * pattern as order-service's aggregator), so a page costs about its slowest call. If a section fails, the others are
 * still returned with {@code degraded: true}.
 */
@Service
public class StorefrontService {

    private static final Logger log = LoggerFactory.getLogger(StorefrontService.class);
    static final int HOME_LIST_SIZE = 8;
    static final int RELATED_SIZE = 4;

    private final ProductAdapter products;
    private final ParallelCalls parallelCalls;

    public StorefrontService(ProductAdapter products, ParallelCalls parallelCalls) {
        this.products = products;
        this.parallelCalls = parallelCalls;
    }

    /** {@code {categories, featured, newArrivals}}: three calls, all at once. */
    public StorefrontPages.Home home() {
        long start = System.nanoTime();
        var categories = parallelCalls.submit("categories", products::categories);
        var featured = parallelCalls.submit("featured", () -> products.products(null, true, "name", HOME_LIST_SIZE));
        var newArrivals = parallelCalls.submit("newArrivals", () -> products.products(null, null, "newest", HOME_LIST_SIZE));

        List<String> unavailable = new ArrayList<>();
        var home = new StorefrontPages.Home(
                orEmpty("categories", categories, unavailable),
                orEmpty("featured", featured, unavailable),
                orEmpty("newArrivals", newArrivals, unavailable),
                !unavailable.isEmpty(), unavailable);
        log.debug("home page: {} ms in total, in parallel; unavailable: {}", (System.nanoTime() - start) / 1_000_000, unavailable);
        return home;
    }

    /**
     * A product plus a few from its category. The product and the category list are fetched in parallel; the related
     * products need the product's category, so they start as soon as the product is there.
     *
     * @throws ProductNotFoundException no such product (404)
     * @throws CatalogUnavailableException the product itself couldn't be loaded (503)
     */
    public StorefrontPages.ProductPage productPage(String slug) {
        var productCall = parallelCalls.submit("product", () -> products.product(slug));
        var categories = parallelCalls.submit("categories", products::categories);

        Catalog.Product product;
        try {
            product = ParallelCalls.await(productCall);   // without the product there is no page
        } catch (ProductNotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CatalogUnavailableException(e);
        }
        var related = product.category() == null
                ? CompletableFuture.completedFuture(List.<Catalog.Product>of())
                : parallelCalls.submit("related", () -> products.products(product.category().slug(), null, "name", RELATED_SIZE + 1));

        List<String> unavailable = new ArrayList<>();
        List<Catalog.Product> sameCategory = orEmpty("related", related, unavailable).stream()
                .filter(p -> !p.id().equals(product.id())).limit(RELATED_SIZE).toList();
        return new StorefrontPages.ProductPage(product, sameCategory, orEmpty("categories", categories, unavailable),
                !unavailable.isEmpty(), unavailable);
    }

    /** A section's result, or an empty list (and its name in {@code unavailable}) if the call failed or timed out. */
    private static <T> List<T> orEmpty(String section, CompletableFuture<List<T>> call, List<String> unavailable) {
        try {
            return ParallelCalls.await(call);
        } catch (RuntimeException e) {
            log.warn("storefront section '{}' unavailable: {}", section, e.toString());
            unavailable.add(section);
            return List.of();
        }
    }
}
