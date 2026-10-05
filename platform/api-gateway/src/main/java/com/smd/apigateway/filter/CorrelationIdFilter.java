package com.smd.apigateway.filter;

import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Every request gets an {@code X-Correlation-Id}: nginx's if it set one, otherwise a new UUID. It is passed to the
 * downstream service (which logs it and forwards it on its own calls) and returned to the client, so one request
 * can be found in every service's logs.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = StringUtils.hasText(incoming) && incoming.length() <= 100 ? incoming : UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> h.set(HEADER, correlationId)).build();
        exchange.getAttributes().put(ATTRIBUTE, correlationId);
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;   // before everything else, so every later log line can use it
    }
}
