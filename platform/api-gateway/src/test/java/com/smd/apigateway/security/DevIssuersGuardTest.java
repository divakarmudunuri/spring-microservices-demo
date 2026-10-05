package com.smd.apigateway.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DevIssuersGuardTest {

    static final IdentityProviderProperties WITH_DEV_ISSUERS = new IdentityProviderProperties(null, null,
            List.of(new IdentityProviderProperties.DevIssuer("http://dev-idp:8080/dev-customer",
                    "http://localhost:8099/dev-customer/jwks", "dev-client", "CUSTOMER", null)));

    @Test
    void startupFailsWhenDevIssuersAreSetOutsideTheLocalProfile() {
        MockEnvironment docker = new MockEnvironment();
        docker.setActiveProfiles("docker");

        assertThatThrownBy(() -> new DevIssuersGuard(WITH_DEV_ISSUERS, docker).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("local");
    }

    @Test
    void devIssuersAreFineInTheLocalProfile() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThatCode(() -> new DevIssuersGuard(WITH_DEV_ISSUERS, local).afterPropertiesSet()).doesNotThrowAnyException();
    }
}
