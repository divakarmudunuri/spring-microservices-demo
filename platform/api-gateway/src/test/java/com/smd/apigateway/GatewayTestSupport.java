package com.smd.apigateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The real gateway on a random port, with:
 * <ul>
 *   <li>one WireMock server per downstream service (found through Spring Cloud's simple discovery client), each
 *       answering {@code {"service":"<name>"}} so a test can see where a path was routed. cart-service points at a
 *       closed port (connection refused); storefront-bff has no instance at all.</li>
 *   <li>user-service's token exchange stubbed: any external token → {@link #INTERNAL_TOKEN}</li>
 *   <li>a fake identity-provider server publishing the JWKS of a fake Google and a fake Okta ({@link TestTokens})</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
public abstract class GatewayTestSupport {

    public static final String GOOGLE_CLIENT_ID = "test-google-client";
    public static final String INTERNAL_TOKEN = "internal-jwt-from-user-service";

    protected static final Map<String, WireMockServer> SERVICES = new LinkedHashMap<>();
    protected static final WireMockServer IDENTITY_PROVIDERS = new WireMockServer(options().dynamicPort());

    static {
        for (String name : new String[]{"user-service", "product-service", "order-service", "shipping-service",
                "order-tracking-service"}) {
            WireMockServer server = new WireMockServer(options().dynamicPort());
            server.start();
            SERVICES.put(name, server);
        }
        IDENTITY_PROVIDERS.start();
        serveJwks("/google/certs", TestTokens.GOOGLE_KEY);
        serveJwks("/okta/oauth2/default/v1/keys", TestTokens.OKTA_KEY);
        serveJwks("/dev/jwks", TestTokens.DEV_KEY);
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        SERVICES.forEach((name, server) ->
                registry.add("spring.cloud.discovery.client.simple.instances." + name + "[0].uri", server::baseUrl));
        registry.add("spring.cloud.discovery.client.simple.instances.cart-service[0].uri", () -> "http://localhost:1");
        registry.add("security.google.client-id", () -> GOOGLE_CLIENT_ID);
        registry.add("security.google.jwk-set-uri", () -> IDENTITY_PROVIDERS.baseUrl() + "/google/certs");
        registry.add("security.okta.issuer-uri", GatewayTestSupport::oktaIssuer);
    }

    @Autowired
    protected WebTestClient client;

    @BeforeEach
    void echoServiceNameAndStubTheExchange() {
        SERVICES.forEach((name, server) -> {
            server.resetAll();
            server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json").withBody("{\"service\":\"" + name + "\"}")));
        });
        stubExchange(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                {"accessToken":"%s","tokenType":"Bearer","expiresAt":"%s","userId":"%s","roles":["CUSTOMER"]}"""
                .formatted(INTERNAL_TOKEN, Instant.now().plusSeconds(300), UUID.randomUUID())));
    }

    protected static void stubExchange(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        service("user-service").stubFor(post(urlEqualTo("/internal/auth/exchange")).atPriority(1).willReturn(response));
    }

    protected static WireMockServer service(String name) {
        return SERVICES.get(name);
    }

    public static String oktaIssuer() {
        return IDENTITY_PROVIDERS.baseUrl() + "/okta/oauth2/default";
    }

    // ---- tokens ----------------------------------------------------------------------------------

    /** A valid Google ID token for a new random customer (each call: a different token). */
    protected static String googleToken() {
        return google(Map.of());
    }

    protected static String google(Map<String, Object> overrides) {
        Map<String, Object> claims = new HashMap<>(Map.of("iss", "https://accounts.google.com", "aud", GOOGLE_CLIENT_ID,
                "sub", "google-" + UUID.randomUUID(), "email", "c@example.com", "email_verified", true));
        claims.putAll(overrides);
        return TestTokens.sign(TestTokens.GOOGLE_KEY, claims);
    }

    protected static String oktaToken(List<String> groups) {
        return TestTokens.sign(TestTokens.OKTA_KEY, Map.of("iss", oktaIssuer(), "aud", "api://default",
                "sub", "admin-" + UUID.randomUUID() + "@example.com", "groups", groups));
    }

    protected static String adminToken() {
        return oktaToken(List.of("smd-admins"));
    }

    private static void serveJwks(String path, com.nimbusds.jose.jwk.RSAKey key) {
        IDENTITY_PROVIDERS.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(TestTokens.jwks(key))));
    }
}
