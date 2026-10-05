package com.smd.apigateway.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * An in-memory {@link RateLimiter} for the gateway's {@code RequestRateLimiter} filter (the built-in one needs Redis).
 * One token bucket per caller key from {@link CallerKeyResolver}, shared by all routes. Buckets of callers that
 * have been quiet for 10 minutes are dropped.
 *
 * <p>Per instance only: with several gateway instances each one counts separately. A Redis-backed limiter would
 * share the counts (CLAUDE.md: only if asked).
 */
@Component("inMemoryRateLimiter")
public class InMemoryRateLimiter implements RateLimiter<Object> {

    private final RateLimitProperties properties;
    private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    public InMemoryRateLimiter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String key) {
        RateLimitProperties.Tier tier = key.startsWith(CallerKeyResolver.USER_PREFIX)
                ? properties.authenticated() : properties.anonymous();
        long now = System.nanoTime();
        long remaining = buckets.get(key, k -> new TokenBucket(tier, now)).tryConsume(now);
        Map<String, String> headers = new HashMap<>();
        headers.put("X-RateLimit-Remaining", Long.toString(Math.max(remaining, 0)));
        headers.put("X-RateLimit-Burst-Capacity", Integer.toString(tier.capacity()));
        headers.put("X-RateLimit-Replenish-Rate", Integer.toString(tier.refillPerSecond()));
        return Mono.just(new Response(remaining >= 0, headers));
    }

    @Override
    public Map<String, Object> getConfig() {
        return Map.of();   // limits come from RateLimitProperties, not per route
    }

    @Override
    public Class<Object> getConfigClass() {
        return Object.class;
    }

    @Override
    public Object newConfig() {
        return new Object();
    }
}
