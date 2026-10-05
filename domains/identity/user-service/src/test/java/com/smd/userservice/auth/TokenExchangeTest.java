package com.smd.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.smd.userservice.PostgresIntegrationTest;
import com.smd.userservice.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** CLAUDE.md 6.11 "user-service exchange" tests, with fake Google, Okta and dev-idp issuers. */
class TokenExchangeTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbc;

    // ---- Google (customers) ------------------------------------------------------------------------

    @Test
    void firstGoogleLoginRegistersExactlyOneCustomerAndReturnsAnInternalToken() throws Exception {
        String subject = "google-" + UUID.randomUUID();

        JsonNode response = json(exchange(google(subject, "New.Customer@Example.com", true)).andExpect(status().isOk()));

        assertThat(rows("GOOGLE", subject)).isOne();
        String userId = jdbc.sql("SELECT id::text FROM users WHERE external_subject = ?").param(subject).query(String.class).single();
        assertThat(jdbc.sql("SELECT email || ' ' || role FROM users WHERE id = ?::uuid").param(userId).query(String.class).single())
                .isEqualTo("new.customer@example.com CUSTOMER");
        assertThat(response.get("userId").asText()).isEqualTo(userId);
        assertThat(response.get("roles").get(0).asText()).isEqualTo("CUSTOMER");

        // the internal token: verifiable with the published JWKS, with the agreed claims
        SignedJWT internal = SignedJWT.parse(response.get("accessToken").asText());
        RSAKey published = JWKSet.parse(mvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse().getContentAsString())
                .getKeys().get(0).toRSAKey();
        assertThat(internal.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(published))).isTrue();
        var claims = internal.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("smd-internal");
        assertThat(claims.getAudience()).containsExactly("smd-api");
        assertThat(claims.getSubject()).isEqualTo(userId);
        assertThat(claims.getStringListClaim("roles")).containsExactly("CUSTOMER");
        assertThat(claims.getStringClaim("idp")).isEqualTo("google");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant())).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void theInternalTokenWorksOnTheApi() throws Exception {
        JsonNode response = json(exchange(google("google-" + UUID.randomUUID(), "api@example.com", true)));

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + response.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("api@example.com"))
                .andExpect(jsonPath("$.defaultAddress").isEmpty());   // a new customer has no address yet
    }

    @Test
    void laterLoginsReuseTheUserAndRefreshTheProfile() throws Exception {
        String subject = "google-" + UUID.randomUUID();
        exchange(google(subject, "old@example.com", true)).andExpect(status().isOk());
        exchange(google(subject, "new@example.com", true)).andExpect(status().isOk());

        assertThat(rows("GOOGLE", subject)).isOne();
        assertThat(jdbc.sql("SELECT email FROM users WHERE external_subject = ?").param(subject).query(String.class).single())
                .isEqualTo("new@example.com");
    }

    @Test
    void concurrentFirstLoginsStillEndWithOneUser() throws Exception {
        String subject = "google-" + UUID.randomUUID();
        String token = google(subject, "race@example.com", true);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return exchange(token).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> r : results) {
            assertThat(r.get()).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(rows("GOOGLE", subject)).isOne();
    }

    @Test
    void invalidGoogleTokensAreRejected() throws Exception {
        String subject = "google-" + UUID.randomUUID();
        Map<String, Object> claims = googleClaims(subject, "x@example.com", true);

        exchange(TestTokens.sign(TestTokens.ROGUE_KEY, claims)).andExpect(status().isUnauthorized())   // forged signature
                .andExpect(jsonPath("$.type").value("/problems/invalid-token"));
        exchange(google(subject, "x@example.com", false)).andExpect(status().isUnauthorized());          // email not verified
        exchange(TestTokens.sign(TestTokens.GOOGLE_KEY, with(claims, "aud", "someone-else"))).andExpect(status().isUnauthorized());
        exchange(TestTokens.sign(TestTokens.GOOGLE_KEY, with(claims, "exp", Date.from(Instant.now().minusSeconds(120)))))
                .andExpect(status().isUnauthorized());                                                      // expired (beyond 60 s skew)
        exchange(TestTokens.sign(TestTokens.GOOGLE_KEY, with(claims, "iss", "https://evil.example.com"))).andExpect(status().isUnauthorized());
        exchange("not-a-jwt").andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/auth/exchange")).andExpect(status().isUnauthorized());
        assertThat(rows("GOOGLE", subject)).isZero();
    }

    // ---- Okta (admins) -----------------------------------------------------------------------------

    @Test
    void oktaLoginInTheAdminGroupRegistersAnAdmin() throws Exception {
        String subject = "okta-" + UUID.randomUUID() + "@example.com";   // Okta's sub is the user's login

        JsonNode response = json(exchange(okta(subject, List.of("smd-admins"))).andExpect(status().isOk()));

        assertThat(response.get("roles").get(0).asText()).isEqualTo("ADMIN");
        assertThat(jdbc.sql("SELECT role FROM users WHERE auth_provider = 'OKTA' AND external_subject = ?").param(subject)
                .query(String.class).single()).isEqualTo("ADMIN");
        assertThat(SignedJWT.parse(response.get("accessToken").asText()).getJWTClaimsSet().getStringClaim("idp")).isEqualTo("okta");
    }

    @Test
    void oktaLoginWithoutTheAdminGroupIsRefused() throws Exception {
        String subject = "okta-" + UUID.randomUUID() + "@example.com";

        exchange(okta(subject, List.of("everyone"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/access-refused"));
        assertThat(rows("OKTA", subject)).isZero();
    }

    // ---- dev identity provider (local profile) -----------------------------------------------------

    @Test
    void devTokensMapToTheSeededSampleUsers() throws Exception {
        JsonNode customer = json(exchange(dev(DEV_CUSTOMER_ISSUER, "sample-customer", "dev-client", null)).andExpect(status().isOk()));
        JsonNode admin = json(exchange(dev(DEV_ADMIN_ISSUER, "sample-admin", "api://default", List.of("smd-admins"))).andExpect(status().isOk()));

        assertThat(customer.get("userId").asText()).isEqualTo("00000000-0000-4000-8000-0000000000c1");
        assertThat(admin.get("userId").asText()).isEqualTo("00000000-0000-4000-8000-0000000000a1");
        assertThat(SignedJWT.parse(admin.get("accessToken").asText()).getJWTClaimsSet().getStringClaim("idp")).isEqualTo("dev");
    }

    @Test
    void devTokensNeverCreateUsers() throws Exception {
        int before = jdbc.sql("SELECT count(*) FROM users").query(Integer.class).single();

        exchange(dev(DEV_CUSTOMER_ISSUER, "somebody-else", "dev-client", null)).andExpect(status().isForbidden());
        exchange(dev(DEV_ADMIN_ISSUER, "sample-not-admin", "api://default", List.of())).andExpect(status().isForbidden());
        exchange(dev(DEV_ADMIN_ISSUER, "sample-admin", "api://default", List.of())).andExpect(status().isForbidden());   // group missing
        exchange(dev(DEV_ADMIN_ISSUER, "sample-customer", "api://default", List.of("smd-admins"))).andExpect(status().isForbidden()); // wrong role

        assertThat(jdbc.sql("SELECT count(*) FROM users").query(Integer.class).single()).isEqualTo(before);
    }

    @Test
    void aSuspendedUserIsRefused() throws Exception {
        String subject = "google-" + UUID.randomUUID();
        exchange(google(subject, "suspended@example.com", true)).andExpect(status().isOk());
        jdbc.sql("UPDATE users SET status = 'SUSPENDED' WHERE external_subject = ?").param(subject).update();

        exchange(google(subject, "suspended@example.com", true)).andExpect(status().isForbidden());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private ResultActions exchange(String token) throws Exception {
        return mvc.perform(post("/internal/auth/exchange").header("Authorization", "Bearer " + token));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private int rows(String provider, String subject) {
        return jdbc.sql("SELECT count(*) FROM users WHERE auth_provider = ? AND external_subject = ?")
                .param(provider).param(subject).query(Integer.class).single();
    }

    static String google(String subject, String email, boolean verified) {
        return TestTokens.sign(TestTokens.GOOGLE_KEY, googleClaims(subject, email, verified));
    }

    static Map<String, Object> googleClaims(String subject, String email, boolean verified) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", GOOGLE_CLIENT_ID);
        claims.put("sub", subject);
        claims.put("email", email);
        claims.put("email_verified", verified);
        claims.put("name", "Test Customer");
        return claims;
    }

    static String okta(String subject, List<String> groups) {
        return TestTokens.sign(TestTokens.OKTA_KEY, Map.of("iss", oktaIssuer(), "aud", "api://default",
                "sub", subject, "groups", groups));
    }

    /** The same claims as dev-idp/dev-idp.json, so a sign-in refreshes the seeded profile with its own values. */
    static final Map<String, List<String>> DEV_PROFILES = Map.of(
            "sample-customer", List.of("customer@demo.local", "Demo Customer"),
            "sample-admin", List.of("admin@demo.local", "Demo Admin"),
            "sample-not-admin", List.of("not-admin@demo.local", "Not An Admin"));

    static String dev(String issuer, String subject, String audience, List<String> groups) {
        List<String> profile = DEV_PROFILES.getOrDefault(subject, List.of(subject + "@demo.local", subject));
        Map<String, Object> claims = new HashMap<>(Map.of("iss", issuer, "aud", audience, "sub", subject,
                "email", profile.get(0), "email_verified", true, "name", profile.get(1)));
        if (groups != null) {
            claims.put("groups", groups);
        }
        return TestTokens.sign(TestTokens.DEV_KEY, claims);
    }

    private static Map<String, Object> with(Map<String, Object> claims, String name, Object value) {
        Map<String, Object> copy = new HashMap<>(claims);
        copy.put(name, value);
        return copy;
    }
}
