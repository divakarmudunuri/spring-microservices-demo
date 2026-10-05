package com.smd.cartservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

/**
 * Layer 3 of the security design (CLAUDE.md 6.11), defense in depth: this service trusts <b>only</b> internal JWTs
 * (issuer {@code smd-internal}, audience {@code smd-api}), whatever the gateway already checked. Roles from the
 * {@code roles} claim; ownership is checked in the code with {@link CurrentUser}.
 * Each service has its own small copy of this class on purpose (no shared library).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    static final String ISSUER = "smd-internal";
    static final String AUDIENCE = "smd-api";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        // guests use carts too: CartController decides (guest by X-Cart-Id, customer by JWT,
                        // admins refused). A token that is present must still be valid (401 otherwise).
                        .requestMatchers("/api/cart", "/api/cart/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter())))
                .csrf(AbstractHttpConfigurer::disable)   // stateless bearer tokens only; CSRF is enforced at nginx
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /** The internal issuer's keys, fetched from user-service by name (Eureka), and cached by the decoder. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${security.internal.jwk-set-uri}") String jwkSetUri, RestTemplate jwksRestTemplate) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).restOperations(jwksRestTemplate).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),   // includes exp / nbf with 60 s skew
                (Jwt jwt) -> jwt.getAudience().contains(AUDIENCE)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "wrong audience", null))));
        return decoder;
    }

    @Bean
    @LoadBalanced
    RestTemplate jwksRestTemplate() {
        return new RestTemplate();
    }

    /** {@code "roles": ["CUSTOMER"]} → authority {@code ROLE_CUSTOMER}. */
    static JwtAuthenticationConverter rolesConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}
