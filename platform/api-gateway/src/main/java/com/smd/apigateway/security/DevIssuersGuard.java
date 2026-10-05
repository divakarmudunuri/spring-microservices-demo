package com.smd.apigateway.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Refuses to start if dev issuers are configured outside the {@code local} profile. */
@Component
public class DevIssuersGuard implements InitializingBean {

    private final IdentityProviderProperties properties;
    private final Environment environment;

    public DevIssuersGuard(IdentityProviderProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.devIssuers().isEmpty() && !environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalStateException("security.dev-issuers is set, but the 'local' profile is not active. "
                    + "Dev identity-provider tokens are only allowed in local development.");
        }
    }
}
