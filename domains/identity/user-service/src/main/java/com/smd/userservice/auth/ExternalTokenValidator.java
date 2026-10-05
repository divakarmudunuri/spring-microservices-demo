package com.smd.userservice.auth;

import com.nimbusds.jwt.JWTParser;
import com.smd.userservice.user.AuthProvider;
import com.smd.userservice.user.UserRole;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Validates a Google, Okta or (local only) dev-idp token <b>itself</b>, with the same rules as the gateway:
 * signature against the issuer's JWKS, {@code iss}, {@code aud}, {@code exp} (60 s skew), and per issuer
 * {@code email_verified} or the admin group. So nobody can get an internal token without a real one, even by
 * reaching this service directly.
 */
@Component
public class ExternalTokenValidator {

    private final Map<String, Issuer> issuers = new LinkedHashMap<>();

    public ExternalTokenValidator(IdentityProviderProperties properties) {
        var google = properties.google();
        if (google != null && google.configured()) {
            add(new Issuer(IdentityProviderProperties.Google.ISSUER, google.jwkSetUri(), google.clientId(),
                    AuthProvider.GOOGLE, "google", UserRole.CUSTOMER, null));
        }
        var okta = properties.okta();
        if (okta != null && okta.configured()) {
            add(new Issuer(okta.issuerUri(), okta.jwkSetUri(), okta.audience(),
                    AuthProvider.OKTA, "okta", UserRole.ADMIN, okta.adminGroup()));
        }
        // DevIssuersGuard has already refused to start if these are set outside the local profile
        properties.devIssuers().forEach(dev -> add(new Issuer(dev.issuer(), dev.jwkSetUri(), dev.audience(),
                AuthProvider.LOCAL, "dev", UserRole.valueOf(dev.role()), dev.requiredGroup())));
    }

    private void add(Issuer issuer) {
        issuers.put(issuer.issuer(), issuer);
    }

    public ExternalIdentity validate(String token) {
        Issuer issuer = issuers.get(unverifiedIssuer(token));
        if (issuer == null) {
            throw new InvalidTokenException("Token from an untrusted issuer");
        }
        Jwt jwt;
        try {
            jwt = issuer.decoder().decode(token);
        } catch (JwtException e) {
            throw new InvalidTokenException("Invalid token: " + e.getMessage());
        }
        List<String> groups = jwt.hasClaim("groups") ? jwt.getClaimAsStringList("groups") : List.of();
        UserRole role = issuer.requiredGroup() == null || groups.contains(issuer.requiredGroup()) ? issuer.role() : null;
        String email = jwt.getClaimAsString("email");
        if (email == null && jwt.getSubject().contains("@")) {
            email = jwt.getSubject();   // Okta access tokens: sub is the login (an email) unless an email claim is added
        }
        String name = jwt.hasClaim("name") ? jwt.getClaimAsString("name") : email;
        return new ExternalIdentity(issuer.provider(), jwt.getSubject(), email, name, role, jwt.getExpiresAt(), issuer.idp());
    }

    /** Only to pick the right decoder; the decoder then verifies the signature and the issuer for real. */
    private static String unverifiedIssuer(String token) {
        try {
            return JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new InvalidTokenException("Malformed token");
        }
    }

    private record Issuer(String issuer, String jwkSetUri, String audience, AuthProvider provider, String idp,
                          UserRole role, String requiredGroup, JwtDecoder decoder) {

        Issuer(String issuer, String jwkSetUri, String audience, AuthProvider provider, String idp, UserRole role,
               String requiredGroup) {
            this(issuer, jwkSetUri, audience, provider, idp, role, requiredGroup,
                    decoder(issuer, jwkSetUri, audience, role == UserRole.CUSTOMER));
        }

        private static JwtDecoder decoder(String issuer, String jwkSetUri, String audience, boolean requireVerifiedEmail) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
            List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
            validators.add(JwtValidators.createDefaultWithIssuer(issuer));   // includes exp/nbf with 60 s skew
            validators.add(claimContains(JwtClaimNames.AUD, audience, "wrong audience"));
            if (requireVerifiedEmail) {
                validators.add(jwt -> Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "email not verified", null)));
            }
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        }

        private static OAuth2TokenValidator<Jwt> claimContains(String claim, String expected, String message) {
            return jwt -> {
                List<String> values = jwt.getClaimAsStringList(claim);
                return values != null && values.contains(expected)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", message, null));
            };
        }
    }
}
