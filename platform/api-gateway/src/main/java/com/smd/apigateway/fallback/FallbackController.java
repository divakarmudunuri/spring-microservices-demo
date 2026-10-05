package com.smd.apigateway.fallback;

import java.net.URI;
import java.util.Set;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * Where a route's circuit breaker sends the request when the service can't be reached, timed out, or the circuit
 * is open: a 503 ProblemDetail instead of a raw connection error. Downstream error responses (e.g. order-service's
 * own 503 with an order id) are not failures for the circuit breaker and pass through unchanged.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<ProblemDetail> serviceUnavailable(@PathVariable String service, ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                service + " is not available right now. Please try again shortly.");
        problem.setType(URI.create("/problems/service-unavailable"));
        problem.setTitle("Service unavailable");
        problem.setProperty("service", service);
        if (cause != null) {
            problem.setProperty("reason", cause.getClass().getSimpleName());
        }
        // the path the client called, not the internal /fallback/... forward
        Set<URI> original = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (original != null && !original.isEmpty()) {
            problem.setInstance(URI.create(original.iterator().next().getPath()));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
