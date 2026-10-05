package com.smd.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/** CLAUDE.md 6.8: in-memory rate limiting per caller; anonymous callers are keyed by client IP. */
@TestPropertySource(properties = {"gateway.rate-limit.anonymous.capacity=3", "gateway.rate-limit.anonymous.refill-per-second=1"})
class RateLimitTest extends GatewayTestSupport {

    @Test
    void anAnonymousCallerIsLimitedAndGets429() {
        List<HttpStatus> statuses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            statuses.add((HttpStatus) client.get().uri("/api/products").header("X-Forwarded-For", "203.0.113.7")
                    .exchange().returnResult(String.class).getStatus());
        }

        assertThat(statuses).containsExactly(HttpStatus.OK, HttpStatus.OK, HttpStatus.OK,
                HttpStatus.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void eachClientIpHasItsOwnBucketAndHeadersShowTheLimit() {
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/categories").header("X-Forwarded-For", "203.0.113.8").exchange().expectStatus().isOk();
        }
        client.get().uri("/api/categories").header("X-Forwarded-For", "203.0.113.8").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        client.get().uri("/api/categories").header("X-Forwarded-For", "203.0.113.9").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Burst-Capacity", "3")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "2");
    }

    @Test
    void onlyTheLastForwardedForEntryCounts() {
        // a client can put anything in X-Forwarded-For; nginx appends the real address last, and only that is trusted
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/products").header("X-Forwarded-For", "10.0.0." + i + ", 198.51.100.1").exchange()
                    .expectStatus().isOk();
        }
        client.get().uri("/api/products").header("X-Forwarded-For", "10.0.0.99, 198.51.100.1").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
