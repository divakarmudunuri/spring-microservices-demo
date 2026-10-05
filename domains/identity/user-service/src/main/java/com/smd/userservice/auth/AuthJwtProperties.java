package com.smd.userservice.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Signing key and lifetime of the internal JWTs.
 *
 * @param privateKeyLocation PKCS#8 PEM, created by scripts/generate-dev-keys.sh locally. Never committed.
 * @param publicKeyLocation  the matching public key (PEM); published as the JWKS
 * @param ttl                lifetime of an internal token (5 minutes)
 * @param ephemeralKey       generate a throwaway key pair in memory instead (tests only)
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record AuthJwtProperties(Resource privateKeyLocation, Resource publicKeyLocation, Duration ttl,
                                boolean ephemeralKey) {
}
