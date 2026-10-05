package com.smd.userservice.auth;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/auth/exchange} with {@code Authorization: Bearer <Google ID token | Okta access token |
 * dev-idp token>}. Internal only: not routed by the gateway, blocked at nginx; and it validates the token itself.
 */
@RestController
public class TokenExchangeController {

    private final TokenExchangeService exchange;

    public TokenExchangeController(TokenExchangeService exchange) {
        this.exchange = exchange;
    }

    @PostMapping("/internal/auth/exchange")
    public ExchangeResponse exchange(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new InvalidTokenException("Missing bearer token");
        }
        var result = exchange.exchange(authorization.substring("Bearer ".length()).trim());
        return new ExchangeResponse(result.token().value(), "Bearer", result.token().expiresAt(),
                result.user().getId(), List.of(result.user().getRole().name()));
    }

    public record ExchangeResponse(String accessToken, String tokenType, Instant expiresAt, UUID userId, List<String> roles) {
    }

    @ExceptionHandler(InvalidTokenException.class)
    ProblemDetail invalidToken(InvalidTokenException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setType(URI.create("/problems/invalid-token"));
        problem.setTitle("Invalid token");
        return problem;
    }

    @ExceptionHandler(AccessRefusedException.class)
    ProblemDetail accessRefused(AccessRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setType(URI.create("/problems/access-refused"));
        problem.setTitle("Access refused");
        return problem;
    }
}
