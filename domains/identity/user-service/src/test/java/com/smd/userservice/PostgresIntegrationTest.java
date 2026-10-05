package com.smd.userservice;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests against a real Postgres 16 with the schema and the {@code local} seed applied, plus a WireMock
 * server publishing the JWKS of a fake Google, a fake Okta and a fake dev identity provider (keys in
 * {@link TestTokens}). The internal signing key is generated in memory. One container, one cached context.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "auth.jwt.ephemeral-key=true"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
public abstract class PostgresIntegrationTest {

    public static final String GOOGLE_CLIENT_ID = "test-google-client";
    public static final String DEV_CUSTOMER_ISSUER = "http://dev-idp.test/dev-customer";
    public static final String DEV_ADMIN_ISSUER = "http://dev-idp.test/dev-admin";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15")
            .withDatabaseName("user_db")
            .withUsername("user_svc");

    protected static final WireMockServer IDENTITY_PROVIDERS = new WireMockServer(options().dynamicPort());

    static {
        POSTGRES.start();
        IDENTITY_PROVIDERS.start();
        serveJwks("/google/certs", TestTokens.jwks(TestTokens.GOOGLE_KEY));
        serveJwks("/okta/oauth2/default/v1/keys", TestTokens.jwks(TestTokens.OKTA_KEY));
        serveJwks("/dev/jwks", TestTokens.jwks(TestTokens.DEV_KEY));
    }

    public static String oktaIssuer() {
        return IDENTITY_PROVIDERS.baseUrl() + "/okta/oauth2/default";
    }

    @DynamicPropertySource
    static void identityProviders(DynamicPropertyRegistry registry) {
        registry.add("security.google.client-id", () -> GOOGLE_CLIENT_ID);
        registry.add("security.google.jwk-set-uri", () -> IDENTITY_PROVIDERS.baseUrl() + "/google/certs");
        registry.add("security.okta.issuer-uri", PostgresIntegrationTest::oktaIssuer);
        // replaces the local profile's list (which points at localhost:8099)
        registry.add("security.dev-issuers[0].issuer", () -> DEV_CUSTOMER_ISSUER);
        registry.add("security.dev-issuers[0].jwk-set-uri", () -> IDENTITY_PROVIDERS.baseUrl() + "/dev/jwks");
        registry.add("security.dev-issuers[0].audience", () -> "dev-client");
        registry.add("security.dev-issuers[0].role", () -> "CUSTOMER");
        registry.add("security.dev-issuers[1].issuer", () -> DEV_ADMIN_ISSUER);
        registry.add("security.dev-issuers[1].jwk-set-uri", () -> IDENTITY_PROVIDERS.baseUrl() + "/dev/jwks");
        registry.add("security.dev-issuers[1].audience", () -> "api://default");
        registry.add("security.dev-issuers[1].role", () -> "ADMIN");
        registry.add("security.dev-issuers[1].required-group", () -> "smd-admins");
    }

    private static void serveJwks(String path, String jwks) {
        IDENTITY_PROVIDERS.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(jwks)));
    }
}
