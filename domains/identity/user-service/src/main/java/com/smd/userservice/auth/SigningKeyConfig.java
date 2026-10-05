package com.smd.userservice.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** The RSA key internal JWTs are signed with. Its public half is served at /.well-known/jwks.json. */
@Configuration
public class SigningKeyConfig {

    @Bean
    public RSAKey signingKey(AuthJwtProperties properties) throws IOException, JOSEException {
        if (properties.ephemeralKey()) {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        }
        if (properties.privateKeyLocation() == null || !properties.privateKeyLocation().exists()) {
            throw new IllegalStateException("No JWT signing key at " + properties.privateKeyLocation()
                    + ". Run ./scripts/generate-dev-keys.sh first (CLAUDE.md section 7).");
        }
        RSAPrivateKey privateKey;
        RSAPublicKey publicKey;
        try (InputStream in = properties.privateKeyLocation().getInputStream()) {
            privateKey = RsaKeyConverters.pkcs8().convert(in);
        }
        try (InputStream in = properties.publicKeyLocation().getInputStream()) {
            publicKey = RsaKeyConverters.x509().convert(in);
        }
        RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        return new RSAKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }
}
