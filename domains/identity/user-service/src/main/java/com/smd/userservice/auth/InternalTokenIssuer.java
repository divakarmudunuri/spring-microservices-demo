package com.smd.userservice.auth;

import com.nimbusds.jose.jwk.RSAKey;
import com.smd.userservice.user.User;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues the internal JWTs every service trusts: RS256, short-lived, {@code sub} = the internal user id.
 * Services never see Google or Okta tokens.
 */
@Component
public class InternalTokenIssuer {

    public static final String ISSUER = "smd-internal";
    public static final String AUDIENCE = "smd-api";

    private final JwtEncoder encoder;
    private final String keyId;
    private final AuthJwtProperties properties;
    private final Clock clock;

    public InternalTokenIssuer(JwtEncoder encoder, RSAKey signingKey, AuthJwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.keyId = signingKey.getKeyID();
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(User user, String idp) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .claim("roles", List.of(user.getRole().name()))
                .claim("idp", idp)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
