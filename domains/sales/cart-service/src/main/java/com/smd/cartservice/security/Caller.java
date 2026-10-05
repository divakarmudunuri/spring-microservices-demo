package com.smd.cartservice.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Who is calling: a customer, an admin, or nobody (a guest). */
@Component
public class Caller {

    /** The customer's id ({@code sub}), if the caller is a signed-in CUSTOMER. */
    public Optional<UUID> customerId() {
        return jwt().filter(a -> hasRole(a, "ROLE_CUSTOMER")).map(a -> UUID.fromString(a.getName()));
    }

    public boolean isAdmin() {
        return jwt().filter(a -> hasRole(a, "ROLE_ADMIN")).isPresent();
    }

    private static Optional<Authentication> jwt() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance);
    }

    private static boolean hasRole(Authentication a, String role) {
        return a.getAuthorities().stream().anyMatch(g -> role.equals(g.getAuthority()));
    }
}
