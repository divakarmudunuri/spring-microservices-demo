package com.smd.apigateway.security;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Writes RFC 7807 bodies from places where no controller is involved (security filters, the exchange filter). */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    /**
     * @param loginUrl where the browser should go to sign in (the frontend's interceptor follows it); null = none
     */
    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String type, String title,
                                   String detail, String loginUrl) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String json = "{\"type\":\"%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"instance\":\"%s\"%s}".formatted(
                type, title, status.value(), escape(detail), escape(exchange.getRequest().getPath().value()),
                loginUrl == null ? "" : ",\"loginUrl\":\"" + loginUrl + "\"");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))));
    }

    /** Admin paths sign in through Okta, everything else through Google (nginx's two oauth2-proxy instances). */
    public static String loginUrlFor(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return path.startsWith("/api/admin") ? "/oauth2/admin/start" : "/oauth2/customer/start";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
