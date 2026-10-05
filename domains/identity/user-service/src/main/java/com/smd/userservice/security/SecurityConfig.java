package com.smd.userservice.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.smd.userservice.auth.InternalTokenIssuer;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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

/**
 * user-service trusts only internal JWTs for its API (defense in depth: the gateway already checked).
 * It is the internal issuer, so it verifies them with its own public key, without fetching its own JWKS.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * The token exchange receives an <em>external</em> token, which it validates itself, and the JWKS is public.
     * Neither may go through the internal-JWT resource server below, or the external token would be rejected.
     */
    @Bean
    @Order(1)
    SecurityFilterChain tokenEndpoints(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/internal/auth/exchange", "/.well-known/jwks.json")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http, Environment environment) throws Exception {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        return http
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll();
                    if (local) {
                        auth.requestMatchers("/internal/chaos").permitAll();
                    }
                    auth.anyRequest().authenticated();   // roles and ownership: @PreAuthorize and CurrentUser
                })
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter())))
                .csrf(AbstractHttpConfigurer::disable)   // stateless bearer tokens only; CSRF is enforced at nginx
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    JwtDecoder internalJwtDecoder(RSAKey signingKey) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(InternalTokenIssuer.ISSUER),
                (Jwt jwt) -> jwt.getAudience().contains(InternalTokenIssuer.AUDIENCE)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "wrong audience", null))));
        return decoder;
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
