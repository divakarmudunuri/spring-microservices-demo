package com.smd.userservice.auth;

import com.smd.userservice.user.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** External token in, internal JWT out (CLAUDE.md 6.11). Called by the gateway, once per few minutes per user. */
@Service
public class TokenExchangeService {

    private final ExternalTokenValidator validator;
    private final UserProvisioning provisioning;
    private final InternalTokenIssuer issuer;

    public TokenExchangeService(ExternalTokenValidator validator, UserProvisioning provisioning, InternalTokenIssuer issuer) {
        this.validator = validator;
        this.provisioning = provisioning;
        this.issuer = issuer;
    }

    public ExchangeResult exchange(String externalToken) {
        ExternalIdentity identity = validator.validate(externalToken);
        User user;
        try {
            user = provisioning.signIn(identity);
        } catch (DataIntegrityViolationException raceLost) {
            // two first logins at once: the other one created the user; this second attempt reads it
            user = provisioning.signIn(identity);
        }
        return new ExchangeResult(user, issuer.issue(user, identity.idp()));
    }

    public record ExchangeResult(User user, InternalTokenIssuer.IssuedToken token) {
    }
}
