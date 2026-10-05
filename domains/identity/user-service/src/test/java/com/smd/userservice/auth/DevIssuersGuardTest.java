package com.smd.userservice.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smd.userservice.TestTokens;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Dev-idp tokens are accepted only in the local profile. */
class DevIssuersGuardTest {

    static final IdentityProviderProperties WITH_DEV_ISSUERS = new IdentityProviderProperties(null, null,
            List.of(new IdentityProviderProperties.DevIssuer("http://dev-idp.test/dev-customer", "http://localhost/jwks",
                    "dev-client", "CUSTOMER", null)));

    @Test
    void startupFailsWhenDevIssuersAreSetOutsideTheLocalProfile() {
        MockEnvironment docker = new MockEnvironment();
        docker.setActiveProfiles("docker");

        assertThatThrownBy(() -> new DevIssuersGuard(WITH_DEV_ISSUERS, docker).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local");
    }

    @Test
    void devIssuersAreFineInTheLocalProfile() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThatCode(() -> new DevIssuersGuard(WITH_DEV_ISSUERS, local).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void withoutDevIssuersADevTokenIsFromAnUntrustedIssuer() {
        var validator = new ExternalTokenValidator(new IdentityProviderProperties(null, null, List.of()));
        String devToken = TestTokens.sign(TestTokens.DEV_KEY, Map.of("iss", "http://dev-idp.test/dev-customer",
                "aud", "dev-client", "sub", "sample-customer"));

        assertThatThrownBy(() -> validator.validate(devToken)).isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("untrusted issuer");
    }
}
