package com.smd.userservice.auth;

import com.smd.userservice.user.AuthProvider;
import com.smd.userservice.user.User;
import com.smd.userservice.user.UserRepository;
import com.smd.userservice.user.UserStatus;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finds (or, for Google and Okta, creates) the user behind an external identity, in one transaction. */
@Service
public class UserProvisioning {

    private final UserRepository users;
    private final Clock clock;

    public UserProvisioning(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /**
     * @throws AccessRefusedException not allowed to sign in
     * @throws org.springframework.dao.DataIntegrityViolationException a concurrent first login created the same
     *         user a moment ago (unique (auth_provider, external_subject)); the caller retries, and finds it
     */
    @Transactional
    public User signIn(ExternalIdentity identity) {
        if (identity.role() == null) {
            throw new AccessRefusedException("Not a member of the admin group");
        }
        User user = users.findByAuthProviderAndExternalSubject(identity.provider(), identity.subject())
                .orElseGet(() -> register(identity));
        if (user.getRole() != identity.role()) {
            throw new AccessRefusedException("This sign-in is for " + identity.role() + " users");
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new AccessRefusedException("This account is suspended");
        }
        user.recordLogin(identity.email(), identity.name(), clock.instant());
        return user;
    }

    private User register(ExternalIdentity identity) {
        if (identity.provider() == AuthProvider.LOCAL) {
            // the dev identity provider never creates users: it may only sign in as a seeded sample user
            throw new AccessRefusedException("Unknown dev user " + identity.subject());
        }
        if (identity.email() == null) {
            throw new InvalidTokenException("Token has no email");
        }
        // just-in-time registration: the first Google sign-in creates the customer, the first Okta sign-in the admin
        return users.saveAndFlush(User.register(identity.provider(), identity.subject(), identity.email(),
                identity.name() != null ? identity.name() : identity.email(), identity.role(), clock.instant()));
    }
}
