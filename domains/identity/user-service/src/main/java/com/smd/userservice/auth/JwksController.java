package com.smd.userservice.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public key of the internal issuer. Every service fetches it (by service name, through Eureka) to verify
 * internal JWTs. Internal only: not routed by the gateway, blocked at nginx.
 */
@RestController
public class JwksController {

    private final Map<String, Object> jwks;

    public JwksController(RSAKey signingKey) {
        this.jwks = new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return jwks;
    }
}
