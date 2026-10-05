package com.smd.apigateway.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * One authentication manager per trusted issuer, picked by the token's {@code iss}
 * ({@link JwtIssuerReactiveAuthenticationManagerResolver}). Each validates the signature against the issuer's JWKS,
 * {@code iss}, {@code aud}, {@code exp} (60 s skew), and grants a role:
 * <ul>
 *   <li>Google → CUSTOMER, only if {@code email_verified == true} (otherwise 401)</li>
 *   <li>Okta → ADMIN, only if {@code groups} contains the admin group; otherwise authenticated with no role (403)</li>
 *   <li>dev issuers ({@code local} only) → the configured role, with the same checks</li>
 * </ul>
 * A token from any other issuer, including the internal {@code smd-internal}, is rejected (401).
 */
@Component
public class TrustedIssuers {

    private final Map<String, ReactiveAuthenticationManager> managers = new HashMap<>();

    public TrustedIssuers(IdentityProviderProperties properties) {
        var google = properties.google();
        if (google != null && google.configured()) {
            add(IdentityProviderProperties.Google.ISSUER, google.jwkSetUri(), google.clientId(), "CUSTOMER", null);
        }
        var okta = properties.okta();
        if (okta != null && okta.configured()) {
            add(okta.issuerUri(), okta.jwkSetUri(), okta.audience(), "ADMIN", okta.adminGroup());
        }
        // DevIssuersGuard has already refused to start if these are set outside the local profile
        properties.devIssuers().forEach(d -> add(d.issuer(), d.jwkSetUri(), d.audience(), d.role(), d.requiredGroup()));
    }

    public JwtIssuerReactiveAuthenticationManagerResolver resolver() {
        return new JwtIssuerReactiveAuthenticationManagerResolver(issuer -> Mono.justOrEmpty(managers.get(issuer)));
    }

    private void add(String issuer, String jwkSetUri, String audience, String role, String requiredGroup) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));   // exp / nbf with 60 s clock skew
        validators.add(audience(audience));
        if ("CUSTOMER".equals(role)) {
            validators.add(EMAIL_VERIFIED);
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));

        JwtReactiveAuthenticationManager manager = new JwtReactiveAuthenticationManager(decoder);
        manager.setJwtAuthenticationConverter(jwt -> Mono.just(new JwtAuthenticationToken(jwt, roles(jwt, role, requiredGroup))));
        managers.put(issuer, manager);
    }

    private static Collection<GrantedAuthority> roles(Jwt jwt, String role, String requiredGroup) {
        if (requiredGroup != null) {
            List<String> groups = jwt.hasClaim("groups") ? jwt.getClaimAsStringList("groups") : List.of();
            if (!groups.contains(requiredGroup)) {
                return List.of();   // a valid token, but no role: every protected route answers 403
            }
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static final OAuth2TokenValidator<Jwt> EMAIL_VERIFIED = jwt ->
            Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "email not verified", null));

    private static OAuth2TokenValidator<Jwt> audience(String expected) {
        return jwt -> {
            List<String> aud = jwt.getClaimAsStringList(JwtClaimNames.AUD);
            return aud != null && aud.contains(expected)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "wrong audience", null));
        };
    }
}
