package com.smd.apigateway.security;

import com.smd.apigateway.GatewayTestSupport;
import com.smd.apigateway.TestTokens;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@code local} profile: the dev identity provider's tokens behave like Google's and Okta's. */
@ActiveProfiles("local")
class DevIssuerSecurityTest extends GatewayTestSupport {

    static final String DEV_CUSTOMER = "http://dev-idp.test/dev-customer";
    static final String DEV_ADMIN = "http://dev-idp.test/dev-admin";

    @DynamicPropertySource
    static void devIssuers(DynamicPropertyRegistry registry) {
        // replaces the local profile's list (which points at localhost:8099)
        registry.add("security.dev-issuers[0].issuer", () -> DEV_CUSTOMER);
        registry.add("security.dev-issuers[0].jwk-set-uri", () -> IDENTITY_PROVIDERS.baseUrl() + "/dev/jwks");
        registry.add("security.dev-issuers[0].audience", () -> "dev-client");
        registry.add("security.dev-issuers[0].role", () -> "CUSTOMER");
        registry.add("security.dev-issuers[1].issuer", () -> DEV_ADMIN);
        registry.add("security.dev-issuers[1].jwk-set-uri", () -> IDENTITY_PROVIDERS.baseUrl() + "/dev/jwks");
        registry.add("security.dev-issuers[1].audience", () -> "api://default");
        registry.add("security.dev-issuers[1].role", () -> "ADMIN");
        registry.add("security.dev-issuers[1].required-group", () -> "smd-admins");
    }

    @Test
    void sampleCustomerIsACustomer() {
        String token = dev(DEV_CUSTOMER, "sample-customer", "dev-client", null);
        client.get().uri("/api/orders").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk();
        client.get().uri("/api/admin/orders").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isForbidden();
    }

    @Test
    void sampleAdminIsAnAdmin() {
        String token = dev(DEV_ADMIN, "sample-admin", "api://default", List.of("smd-admins"));
        client.get().uri("/api/admin/orders").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk();
    }

    @Test
    void sampleNotAdminIsRefusedOnAdminRoutes() {
        String token = dev(DEV_ADMIN, "sample-not-admin", "api://default", List.of());
        client.get().uri("/api/admin/orders").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isForbidden();
    }

    @Test
    void theAngularDevServerMayCallTheApiInLocal() {
        client.options().uri("/api/products")
                .header("Origin", "http://localhost:4200").header("Access-Control-Request-Method", "GET").exchange()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:4200");
        client.options().uri("/api/products")
                .header("Origin", "https://evil.example.com").header("Access-Control-Request-Method", "GET").exchange()
                .expectStatus().isForbidden();
    }

    private static String dev(String issuer, String subject, String audience, List<String> groups) {
        Map<String, Object> claims = new HashMap<>(Map.of("iss", issuer, "aud", audience, "sub", subject, "email_verified", true));
        if (groups != null) {
            claims.put("groups", groups);
        }
        return TestTokens.sign(TestTokens.DEV_KEY, claims);
    }
}
