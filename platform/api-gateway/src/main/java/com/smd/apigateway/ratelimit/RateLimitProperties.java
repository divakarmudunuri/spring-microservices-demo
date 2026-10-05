package com.smd.apigateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token buckets per caller: an authenticated user ({@code sub}) gets more than an anonymous IP address
 * (catalog browsing, guest carts). nginx also limits per IP at the edge.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(Tier authenticated, Tier anonymous) {

    /**
     * @param capacity        burst: requests allowed at once
     * @param refillPerSecond sustained rate
     */
    public record Tier(int capacity, int refillPerSecond) {
    }
}
