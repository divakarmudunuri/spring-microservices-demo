package com.smd.storefrontbff.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smd.storefrontbff.client.product.ProductNotFoundException;
import com.smd.storefrontbff.storefront.CatalogUnavailableException;
import com.smd.storefrontbff.storefront.StorefrontPages;
import com.smd.storefrontbff.storefront.StorefrontService;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public storefront pages. */
@RestController
@RequestMapping("/api/storefront")
public class StorefrontController {

    private final StorefrontService storefront;

    /**
     * The home page for anonymous visitors, kept for 30 s: the same for everyone, and the most-requested page.
     * A degraded page is not cached (it would hide the recovery for 30 s). Signed-in callers are never served
     * from it, in case a page ever depends on who is asking.
     */
    private final Cache<String, StorefrontPages.Home> anonymousHome = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(1).build();

    public StorefrontController(StorefrontService storefront) {
        this.storefront = storefront;
    }

    @GetMapping("/home")
    public StorefrontPages.Home home(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (authorization != null) {
            return storefront.home();
        }
        StorefrontPages.Home cached = anonymousHome.getIfPresent("home");
        if (cached != null) {
            return cached;
        }
        StorefrontPages.Home home = storefront.home();
        if (!home.degraded()) {
            anonymousHome.put("home", home);
        }
        return home;
    }

    @GetMapping("/products/{slug}")
    public StorefrontPages.ProductPage product(@PathVariable String slug) {
        return storefront.productPage(slug);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail notFound(ProductNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No such product");
        problem.setType(URI.create("/problems/product-not-found"));
        problem.setTitle("Product not found");
        return problem;
    }

    /** The product itself couldn't be loaded (its page has nothing to show without it). */
    @ExceptionHandler(CatalogUnavailableException.class)
    ProblemDetail unavailable(CatalogUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The catalog is not available right now. Please try again shortly.");
        problem.setType(URI.create("/problems/dependency-unavailable"));
        problem.setTitle("Dependency unavailable");
        return problem;
    }
}
