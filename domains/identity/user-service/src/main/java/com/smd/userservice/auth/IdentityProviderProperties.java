package com.smd.userservice.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * The external issuers whose tokens can be exchanged for an internal JWT. The gateway trusts the same ones
 * (CLAUDE.md 6.11). An issuer that isn't configured (no Google client id, no Okta domain) is simply not trusted.
 */
@ConfigurationProperties(prefix = "security")
public record IdentityProviderProperties(Google google, Okta okta, @DefaultValue List<DevIssuer> devIssuers) {

    /** Customers. ID tokens: {@code iss=https://accounts.google.com}, {@code aud} = our client id. */
    public record Google(String clientId, String jwkSetUri) {

        public static final String ISSUER = "https://accounts.google.com";

        public boolean configured() {
            return StringUtils.hasText(clientId);
        }
    }

    /** Admins. Access tokens from the custom authorization server {@code default}. */
    public record Okta(String issuerUri, String audience, String adminGroup) {

        public boolean configured() {
            return StringUtils.hasText(issuerUri);
        }

        public String jwkSetUri() {
            return issuerUri + "/v1/keys";
        }
    }

    /**
     * {@code local} only: the mock OIDC server in dev-idp/. Its issuer string and key URL differ (the browser and
     * dev-token.sh reach it on localhost:8099, oauth2-proxy as dev-idp:8080), so both are configured explicitly.
     */
    public record DevIssuer(String issuer, String jwkSetUri, String audience, String role, String requiredGroup) {
    }
}
