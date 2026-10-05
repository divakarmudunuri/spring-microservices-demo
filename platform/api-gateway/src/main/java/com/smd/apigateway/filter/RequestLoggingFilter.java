package com.smd.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * One line per request: method, path, route, status, duration and correlation id. The trace id is in the log
 * pattern itself ({@code spring.reactor.context-propagation=auto} makes it available on reactive threads).
 */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            log.info("{} {} → {} {} in {} ms [correlationId={}]",
                    exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                    route == null ? "-" : route.getId(), exchange.getResponse().getStatusCode(),
                    (System.nanoTime() - start) / 1_000_000, exchange.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
