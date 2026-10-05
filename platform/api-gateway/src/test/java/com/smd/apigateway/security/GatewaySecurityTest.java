package com.smd.apigateway.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.smd.apigateway.GatewayTestSupport;
import com.smd.apigateway.TestTokens;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** CLAUDE.md 6.11 gateway tests, with a fake Google and a fake Okta issuer. Default profile (no dev issuers). */
@TestPropertySource(properties = {"gateway.rate-limit.anonymous.capacity=10000", "gateway.rate-limit.anonymous.refill-per-second=10000",
        "gateway.rate-limit.authenticated.capacity=10000", "gateway.rate-limit.authenticated.refill-per-second=10000"})
class GatewaySecurityTest extends GatewayTestSupport {

    @Test
    void googleTokenGetsCustomerAccessAndIsExchangedForAnInternalToken() {
        String google = googleToken();

        get("/api/orders", google).expectStatus().isOk();

        // the service sees the internal token, never Google's
        service("order-service").verify(anyRequestedFor(urlPathEqualTo("/api/orders"))
                .withHeader("Authorization", equalTo("Bearer " + INTERNAL_TOKEN)));
        service("user-service").verify(postRequestedFor(urlEqualTo("/internal/auth/exchange"))
                .withHeader("Authorization", equalTo("Bearer " + google)));
    }

    @Test
    void invalidGoogleTokensAre401() {
        get("/api/orders", google(Map.of("aud", "another-client"))).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.type").isEqualTo("/problems/unauthorized");
        get("/api/orders", google(Map.of("email_verified", false))).expectStatus().isUnauthorized();
        get("/api/orders", google(Map.of("exp", Date.from(Instant.now().minusSeconds(120))))).expectStatus().isUnauthorized();
        get("/api/orders", TestTokens.sign(TestTokens.ROGUE_KEY, Map.of("iss", "https://accounts.google.com",
                "aud", GOOGLE_CLIENT_ID, "sub", "x", "email_verified", true))).expectStatus().isUnauthorized();   // forged
    }

    @Test
    void oktaTokenWithTheAdminGroupGetsAdminAccess() {
        get("/api/admin/orders", adminToken()).expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("order-service");
    }

    @Test
    void oktaTokenWithoutTheGroupIs403() {
        get("/api/admin/orders", oktaToken(List.of("everyone"))).expectStatus().isForbidden()
                .expectBody().jsonPath("$.type").isEqualTo("/problems/forbidden");
        get("/api/orders", oktaToken(List.of())).expectStatus().isForbidden();   // and no customer access either
    }

    @Test
    void tokensFromAnyOtherIssuerAre401() {
        get("/api/orders", TestTokens.sign(TestTokens.GOOGLE_KEY, Map.of("iss", "https://evil.example.com",
                "aud", GOOGLE_CLIENT_ID, "sub", "x"))).expectStatus().isUnauthorized();
        // an internal token sent from outside: the gateway never trusts smd-internal
        get("/api/orders", TestTokens.sign(TestTokens.GOOGLE_KEY, Map.of("iss", "smd-internal", "aud", "smd-api",
                "sub", "00000000-0000-4000-8000-0000000000c1", "roles", List.of("CUSTOMER")))).expectStatus().isUnauthorized();
        // outside the local profile, the dev identity provider is just another unknown issuer
        get("/api/orders", TestTokens.sign(TestTokens.DEV_KEY, Map.of("iss", "http://localhost:8099/dev-customer",
                "aud", "dev-client", "sub", "sample-customer", "email_verified", true))).expectStatus().isUnauthorized();
        get("/api/orders", "not-a-jwt").expectStatus().isUnauthorized();
    }

    @Test
    void publicRoutesWorkWithoutATokenButRejectABadOne() {
        get("/api/products", null).expectStatus().isOk();
        service("product-service").verify(anyRequestedFor(urlPathEqualTo("/api/products")).withHeader("Authorization", absent()));
        get("/api/products", "not-a-jwt").expectStatus().isUnauthorized();   // a token that is present must be valid
    }

    @Test
    void anonymousOnAProtectedRouteGets401WithWhereToSignIn() {
        get("/api/orders", null).expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody().jsonPath("$.loginUrl").isEqualTo("/oauth2/customer/start");
        get("/api/admin/orders", null).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.loginUrl").isEqualTo("/oauth2/admin/start");
    }

    @Test
    void theExchangeIsCachedPerToken() {
        String google = googleToken();
        for (int i = 0; i < 5; i++) {
            get("/api/orders", google).expectStatus().isOk();
        }
        service("user-service").verify(1, postRequestedFor(urlEqualTo("/internal/auth/exchange")));

        get("/api/orders", googleToken()).expectStatus().isOk();   // another token: another exchange
        service("user-service").verify(2, postRequestedFor(urlEqualTo("/internal/auth/exchange")));
    }

    @Test
    void aSuspendedUserIs403() {
        stubExchange(aResponse().withStatus(403).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"title\":\"Access refused\",\"detail\":\"This account is suspended\"}"));

        get("/api/orders", googleToken()).expectStatus().isForbidden();
        service("order-service").verify(0, anyRequestedFor(urlPathEqualTo("/api/orders")));
    }

    @Test
    void userServiceDownIs503() {
        stubExchange(aResponse().withStatus(500));

        get("/api/orders", googleToken()).expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.type").isEqualTo("/problems/service-unavailable");
    }

    /** The route table of CLAUDE.md 6.11, row by row, for each kind of caller. */
    @ParameterizedTest(name = "{0} {1}: anonymous {2}, customer {3}, admin {4}")
    @CsvSource({
            "GET,    /actuator/health,                    200, 200, 200",
            "GET,    /api/products,                       200, 200, 200",
            "GET,    /api/categories,                     200, 200, 200",
            "POST,   /api/products,                       401, 403, 403",
            "GET,    /api/admin/orders,                   401, 403, 200",
            "POST,   /api/admin/inventory/x/restock,      401, 403, 200",
            "GET,    /api/admin/me,                       401, 403, 200",
            "GET,    /api/admin/shipments,                401, 403, 200",
            "GET,    /api/admin/tracking/orders/x,        401, 403, 200",
            "POST,   /api/orders/checkout,                401, 200, 403",
            "GET,    /api/orders,                         401, 200, 403",
            "GET,    /api/wallet,                         401, 200, 403",
            "GET,    /api/tracking/orders/x,              401, 200, 403",
            "GET,    /api/shipments/by-order/x,           401, 200, 403",
            "GET,    /api/users/me,                       401, 200, 403",
            "PUT,    /api/users/me/address,               401, 200, 403",
            "DELETE, /api/users/me,                       401, 403, 403",
            "GET,    /api/users/x,                        401, 403, 403",
            "GET,    /internal/chaos,                     401, 403, 403",
    })
    void routeTable(String method, String path, int anonymous, int customer, int admin) {
        call(method, path, null).expectStatus().isEqualTo(anonymous);
        call(method, path, googleToken()).expectStatus().isEqualTo(customer);
        call(method, path, adminToken()).expectStatus().isEqualTo(admin);
    }

    private WebTestClient.ResponseSpec get(String path, String token) {
        return call("GET", path, token);
    }

    private WebTestClient.ResponseSpec call(String method, String path, String token) {
        return client.method(HttpMethod.valueOf(method)).uri(path)
                .headers(h -> { if (token != null) h.setBearerAuth(token); })
                .exchange();
    }
}
