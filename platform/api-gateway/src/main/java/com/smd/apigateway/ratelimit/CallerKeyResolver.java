package com.smd.apigateway.ratelimit;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Who is calling, for rate limiting: {@code user:<sub>} when authenticated (from phase 11), otherwise
 * {@code ip:<client address>}.
 *
 * <p>The client address comes from {@code X-Forwarded-For}, trusting exactly one proxy hop (nginx appends the
 * real client address as the last entry). Earlier entries could be set by the client itself, so they are ignored.
 * Without the header (direct calls in {@code local}) the socket address is used.
 */
@Component("callerKeyResolver")
public class CallerKeyResolver implements KeyResolver {

    static final String USER_PREFIX = "user:";
    static final String IP_PREFIX = "ip:";

    private final XForwardedRemoteAddressResolver clientAddress = XForwardedRemoteAddressResolver.maxTrustedIndex(1);

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(principal -> USER_PREFIX + principal.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> IP_PREFIX + ip(exchange)));
    }

    private String ip(ServerWebExchange exchange) {
        InetSocketAddress address = clientAddress.resolve(exchange);
        if (address == null) {
            return "unknown";
        }
        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
    }
}
