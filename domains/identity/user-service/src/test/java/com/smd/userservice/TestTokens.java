package com.smd.userservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/** Signing keys for fake identity providers, generated per test run, and tokens signed with them. */
public final class TestTokens {

    public static final RSAKey GOOGLE_KEY = key("google");
    public static final RSAKey OKTA_KEY = key("okta");
    public static final RSAKey DEV_KEY = key("dev");
    /** Not published anywhere: a token signed with it has a forged signature. */
    public static final RSAKey ROGUE_KEY = key("google");

    private TestTokens() {
    }

    public static String sign(RSAKey key, Map<String, Object> claims) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(600)));
            claims.forEach(builder::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), builder.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String jwks(RSAKey key) {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    private static RSAKey key(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
