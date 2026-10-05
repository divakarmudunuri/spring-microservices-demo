package com.smd.apigateway.security;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Layer 2 of the security design (CLAUDE.md 6.11): who may call which route. The gateway only sees bearer tokens
 * (nginx turned the session cookie into one), so it is stateless and has no CSRF protection of its own: CSRF is
 * enforced at nginx. Anything not listed below is denied.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, TrustedIssuers trustedIssuers,
                                                  ObjectProvider<CorsConfigurationSource> cors, Environment environment) {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)          // bearer tokens only; CSRF is checked at nginx
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())   // stateless
                .authorizeExchange(auth -> {
                    auth.pathMatchers(HttpMethod.GET, "/actuator/health/**").permitAll();
                    auth.pathMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll();   // scraped internally; nginx blocks /actuator
                    if (local) {
                        auth.pathMatchers("/actuator/**").permitAll();
                    }
                    auth.pathMatchers(HttpMethod.GET, "/api/products/**", "/api/categories/**", "/api/storefront/**").permitAll()
                            // cart-service decides: a guest by X-Cart-Id, a customer by the JWT
                            .pathMatchers("/api/cart/**").permitAll()
                            .pathMatchers("/api/admin/**").hasRole("ADMIN")
                            // ownership is checked by each service
                            .pathMatchers("/api/orders/**", "/api/wallet/**", "/api/tracking/**", "/api/shipments/**").hasRole("CUSTOMER")
                            .pathMatchers(HttpMethod.GET, "/api/users/me/**").hasRole("CUSTOMER")
                            .pathMatchers(HttpMethod.PUT, "/api/users/me/**").hasRole("CUSTOMER")
                            .anyExchange().denyAll();
                })
                // On every route, public ones included, a bearer token that is present must be valid (else 401).
                .oauth2ResourceServer(rs -> rs
                        .authenticationManagerResolver(trustedIssuers.resolver())
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized()).accessDeniedHandler(forbidden()));
        if (cors.getIfAvailable() != null) {
            http.cors(c -> c.configurationSource(cors.getObject()));
        }
        return http.build();
    }

    /** {@code local} only: the Angular dev server on :4200. Through nginx everything is same-origin. */
    @Bean
    @Profile("local")
    CorsConfigurationSource localDevServerCors() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:4200"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "X-Cart-Id", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "X-Cart-Id", "Location"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static ServerAuthenticationEntryPoint unauthorized() {
        return (exchange, e) -> {
            exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
            return ProblemResponses.write(exchange, HttpStatus.UNAUTHORIZED, "/problems/unauthorized", "Unauthorized",
                    "Sign in to continue, or the token is invalid or expired", ProblemResponses.loginUrlFor(exchange));
        };
    }

    private static ServerAccessDeniedHandler forbidden() {
        return (exchange, e) -> ProblemResponses.write(exchange, HttpStatus.FORBIDDEN, "/problems/forbidden", "Forbidden",
                "Your account may not use this", null);
    }
}
