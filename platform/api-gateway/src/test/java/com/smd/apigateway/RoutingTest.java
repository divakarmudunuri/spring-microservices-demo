package com.smd.apigateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/** CLAUDE.md 6.8: every route goes to its service; everything else is denied. */
@TestPropertySource(properties = {"gateway.rate-limit.anonymous.capacity=10000", "gateway.rate-limit.anonymous.refill-per-second=10000"})
class RoutingTest extends GatewayTestSupport {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "GET,    /api/users/me,                          user-service",
            "PUT,    /api/users/me/address,                  user-service",
            "GET,    /api/admin/me,                          user-service",
            "GET,    /api/products,                          product-service",
            "GET,    /api/products/mechanical-keyboard,      product-service",
            "GET,    /api/categories,                        product-service",
            "POST,   /api/orders,                            order-service",
            "GET,    /api/orders/123/details,                order-service",
            "GET,    /api/wallet,                            order-service",
            "GET,    /api/admin/orders,                      order-service",
            "GET,    /api/admin/payments,                    order-service",
            "POST,   /api/admin/inventory/123/restock,       order-service",
            "GET,    /api/admin/shipments,                   shipping-service",
            "GET,    /api/shipments/by-order/123,            shipping-service",
            "GET,    /api/tracking/orders/123,               order-tracking-service",
            "GET,    /api/admin/tracking/orders/123,         order-tracking-service",
    })
    void routesToTheRightService(String method, String path, String expectedService) {
        String token = path.startsWith("/api/admin") ? adminToken()
                : path.startsWith("/api/products") || path.startsWith("/api/categories") ? null : googleToken();
        client.method(HttpMethod.valueOf(method)).uri(path)
                .headers(h -> { if (token != null) h.setBearerAuth(token); })
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo(expectedService);
        service(expectedService).verify(anyRequestedFor(urlPathEqualTo(path)));   // path forwarded unchanged
    }

    @ParameterizedTest(name = "{0} {1} is denied (customer: {2})")
    @CsvSource({
            "GET,    /api/users/00000000-0000-4000-8000-0000000000c1, 403",   // internal: service-to-service only
            "GET,    /internal/chaos,                                 403",
            "POST,   /internal/auth/exchange,                         403",
            "GET,    /api/fulfillments,                               403",   // fulfillment-service is never exposed
            "GET,    /.well-known/jwks.json,                          403",
            "GET,    /eureka/apps,                                    403",
            "POST,   /api/shipments/by-order/123,                     404",   // allowed for customers, but no POST route
    })
    void everythingElseIsDenied(String method, String path, int customerStatus) {
        // anything not in the route table is denied: anonymous → 401, a signed-in customer → 403 (or 404: no route)
        client.method(HttpMethod.valueOf(method)).uri(path).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith("application/problem+json");
        client.method(HttpMethod.valueOf(method)).uri(path).headers(h -> h.setBearerAuth(googleToken())).exchange()
                .expectStatus().isEqualTo(customerStatus);
        SERVICES.forEach((name, s) -> assertThat(s.getAllServeEvents())
                .filteredOn(e -> !e.getRequest().getUrl().equals("/internal/auth/exchange")).isEmpty());
    }

    @Test
    void correlationIdIsGeneratedPassedOnAndReturned() {
        String returned = client.get().uri("/api/products").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");

        assertThat(returned).matches("[0-9a-f-]{36}");
        service("product-service").verify(anyRequestedFor(urlPathEqualTo("/api/products"))
                .withHeader("X-Correlation-Id", equalTo(returned)));
    }

    @Test
    void anIncomingCorrelationIdIsKept() {
        client.get().uri("/api/categories").header("X-Correlation-Id", "from-nginx-42").exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "from-nginx-42");
        service("product-service").verify(anyRequestedFor(urlPathEqualTo("/api/categories"))
                .withHeader("X-Correlation-Id", equalTo("from-nginx-42")));
    }

    @Test
    void downstreamErrorsPassThroughUnchanged() {
        service("order-service").stubFor(any(anyUrl()).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"type\":\"/problems/dependency-unavailable\",\"orderId\":\"o-1\"}")));

        client.post().uri("/api/orders").headers(h -> h.setBearerAuth(googleToken())).exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().jsonPath("$.orderId").isEqualTo("o-1");   // order-service's body, not the gateway fallback
    }

    @Test
    void unreachableServiceGetsTheFallbackProblem() {
        client.get().uri("/api/cart").exchange()                       // cart-service: connection refused
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody()
                .jsonPath("$.type").isEqualTo("/problems/service-unavailable")
                .jsonPath("$.service").isEqualTo("cart-service")
                .jsonPath("$.instance").isEqualTo("/api/cart");
    }

    @Test
    void serviceWithNoInstanceGetsTheFallbackProblem() {
        client.get().uri("/api/storefront/home").exchange()            // storefront-bff: not registered at all
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().jsonPath("$.service").isEqualTo("storefront-bff");
    }

    @Test
    void slowServiceTimesOutIntoTheFallback() {
        service("order-tracking-service").stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withFixedDelay(3_500).withBody("{}")));                // route response-timeout: 3000 ms

        long start = System.nanoTime();
        client.get().uri("/api/tracking/orders/123").headers(h -> h.setBearerAuth(googleToken())).exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().jsonPath("$.service").isEqualTo("order-tracking-service");
        assertThat((System.nanoTime() - start) / 1_000_000).isBetween(2_900L, 3_400L);
    }
}
