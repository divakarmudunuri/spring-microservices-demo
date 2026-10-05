package com.smd.userservice.auth;

import com.smd.userservice.user.AuthProvider;
import com.smd.userservice.user.UserRole;
import java.time.Instant;

/**
 * Who a valid external token says the caller is.
 *
 * @param provider  GOOGLE, OKTA, or LOCAL for the dev identity provider
 * @param role      the role this issuer grants; null if the issuer requires a group the token doesn't have
 * @param idp       {@code google} / {@code okta} / {@code dev}, copied into the internal token
 */
public record ExternalIdentity(AuthProvider provider, String subject, String email, String name, UserRole role,
                               Instant expiresAt, String idp) {
}
