package com.smd.apigateway.exchange;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

/**
 * Asks user-service to exchange an external token for an internal JWT, and caches the answer until 30 s before
 * the earlier of the two expiries. Normal browsing costs one exchange every few minutes per user, not one per
 * request. Cache key: SHA-256 of the external token (the token itself is never kept as a key).
 */
@Component
public class TokenExchangeClient {

    static final Duration SAFETY_MARGIN = Duration.ofSeconds(30);

    private final WebClient userService;
    private final Clock clock;
    private final Duration timeout;
    private final Cache<String, CachedToken> cache = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfter(Expiry.<String, CachedToken>creating((key, token) -> token.ttl()))
            .build();

    public TokenExchangeClient(WebClient.Builder loadBalancedWebClientBuilder, Clock clock,
                               @Value("${gateway.token-exchange.timeout:3s}") Duration timeout) {
        this.userService = loadBalancedWebClientBuilder.baseUrl("http://user-service").build();
        this.clock = clock;
        this.timeout = timeout;
    }

    /** @param externalExpiresAt the external token's own {@code exp} */
    public Mono<String> internalToken(String externalToken, Instant externalExpiresAt) {
        String key = sha256(externalToken);
        CachedToken cached = cache.getIfPresent(key);
        if (cached != null) {
            return Mono.just(cached.value());
        }
        return userService.post().uri("/internal/auth/exchange")
                .headers(h -> h.setBearerAuth(externalToken))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, r -> Mono.error(new ExchangeFailedException(
                        r.statusCode().value() == 403 ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                        "Token exchange refused (" + r.statusCode().value() + ")", null)))
                .onStatus(HttpStatusCode::is5xxServerError, r -> Mono.error(new ExchangeFailedException(
                        HttpStatus.SERVICE_UNAVAILABLE, "user-service answered " + r.statusCode().value(), null)))
                .bodyToMono(ExchangeResponse.class)
                .timeout(timeout)
                .onErrorMap(e -> !(e instanceof ExchangeFailedException),
                        e -> new ExchangeFailedException(HttpStatus.SERVICE_UNAVAILABLE, "user-service is unavailable", e))
                .doOnNext(response -> {
                    Instant earlier = externalExpiresAt != null && externalExpiresAt.isBefore(response.expiresAt())
                            ? externalExpiresAt : response.expiresAt();
                    Duration ttl = Duration.between(clock.instant(), earlier).minus(SAFETY_MARGIN);
                    if (!ttl.isNegative() && !ttl.isZero()) {
                        cache.put(key, new CachedToken(response.accessToken(), ttl));
                    }
                })
                .map(ExchangeResponse::accessToken);
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record ExchangeResponse(String accessToken, Instant expiresAt) {
    }

    private record CachedToken(String value, Duration ttl) {
    }
}
