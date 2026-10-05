package com.smd.apigateway.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * The external issuers the gateway trusts (user-service trusts the same ones; each keeps its own copy of this
 * configuration, no shared library). An issuer that isn't configured is simply not trusted. The internal issuer
 * ({@code smd-internal}) is never trusted here: internal tokens must not come from outside.
 */
@ConfigurationProperties(prefix = "security")
public record IdentityProviderProperties(Google google, Okta okta, @DefaultValue List<DevIssuer> devIssuers) {

    /** Customers: Google ID tokens. */
    public record Google(String clientId, String jwkSetUri) {

        public static final String ISSUER = "https://accounts.google.com";

        public boolean configured() {
            return StringUtils.hasText(clientId);
        }
    }

    /** Admins: Okta access tokens from the custom authorization server {@code default}. */
    public record Okta(String issuerUri, String audience, String adminGroup) {

        public boolean configured() {
            return StringUtils.hasText(issuerUri);
        }

        public String jwkSetUri() {
            return issuerUri + "/v1/keys";
        }
    }

    /** {@code local} only: the mock OIDC server in dev-idp/ (see dev-idp/README.md). */
    public record DevIssuer(String issuer, String jwkSetUri, String audience, String role, String requiredGroup) {
    }
}
