package com.smd.apigateway.exchange;

import com.smd.apigateway.security.ProblemResponses;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * After authentication, before routing: replaces the caller's Google / Okta / dev-idp token with an internal JWT
 * from user-service. <b>Services never see an external token.</b> Anonymous requests pass through without any
 * Authorization header.
 */
@Component
public class TokenExchangeFilter implements GlobalFilter, Ordered {

    private final TokenExchangeClient client;

    public TokenExchangeFilter(TokenExchangeClient client) {
        this.client = client;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .flatMap(auth -> client.internalToken(auth.getToken().getTokenValue(), auth.getToken().getExpiresAt()))
                .map(internal -> withAuthorization(exchange, internal))
                .defaultIfEmpty(withAuthorization(exchange, null))
                .flatMap(chain::filter)
                .onErrorResume(ExchangeFailedException.class, e -> failed(exchange, e));
    }

    private static ServerWebExchange withAuthorization(ServerWebExchange exchange, String internalToken) {
        return exchange.mutate().request(r -> r.headers(h -> {
            h.remove(HttpHeaders.AUTHORIZATION);
            if (internalToken != null) {
                h.setBearerAuth(internalToken);
            }
        })).build();
    }

    private static Mono<Void> failed(ServerWebExchange exchange, ExchangeFailedException e) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(e);
        }
        return switch (e.status()) {
            case FORBIDDEN -> ProblemResponses.write(exchange, HttpStatus.FORBIDDEN, "/problems/forbidden", "Forbidden",
                    "This account may not sign in (suspended, or not allowed here)", null);
            case UNAUTHORIZED -> ProblemResponses.write(exchange, HttpStatus.UNAUTHORIZED, "/problems/unauthorized",
                    "Unauthorized", "The token was not accepted", ProblemResponses.loginUrlFor(exchange));
            default -> ProblemResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "/problems/service-unavailable",
                    "Service unavailable", "Sign-in is temporarily unavailable. Please try again shortly.", null);
        };
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;   // after correlation id and logging, before the route filters
    }
}
