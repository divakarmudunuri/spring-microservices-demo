package com.smd.apigateway.ratelimit;

/** A classic token bucket, refilled lazily from the elapsed time. Thread-safe. */
final class TokenBucket {

    private final int capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastRefill;

    TokenBucket(RateLimitProperties.Tier tier, long now) {
        this.capacity = tier.capacity();
        this.refillPerNano = tier.refillPerSecond() / 1_000_000_000.0;
        this.tokens = tier.capacity();
        this.lastRefill = now;
    }

    /** @return the tokens left after taking one, or -1 if there was none to take */
    synchronized long tryConsume(long now) {
        tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
        lastRefill = now;
        if (tokens < 1) {
            return -1;
        }
        tokens -= 1;
        return (long) tokens;
    }
}
