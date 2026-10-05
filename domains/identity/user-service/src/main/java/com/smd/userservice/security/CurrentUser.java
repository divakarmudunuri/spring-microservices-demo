package com.smd.userservice.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The caller, from the internal JWT: {@code sub} is the internal user id. */
@Component
public class CurrentUser {

    public UUID id() {
        return UUID.fromString(authentication().getName());
    }

    public boolean isAdmin() {
        return authentication().getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException("No authenticated caller");
        }
        return authentication;
    }
}
