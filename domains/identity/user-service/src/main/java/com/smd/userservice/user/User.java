package com.smd.userservice.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    /** The internal user id: used as {@code user_id} in every other service. */
    @Id
    private UUID id;

    private String email;

    private String fullName;

    @Enumerated(EnumType.STRING)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    private AuthProvider authProvider;

    private String externalSubject;

    private Instant lastLoginAt;

    // maintained by column defaults and the set_updated_at() trigger
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected User() {
        // for JPA
    }

    /** Just-in-time registration on the first Google / Okta sign-in. */
    public static User register(AuthProvider provider, String externalSubject, String email, String fullName,
                                UserRole role, Instant now) {
        User user = new User();
        user.id = UUID.randomUUID();
        user.authProvider = provider;
        user.externalSubject = externalSubject;
        user.email = email.toLowerCase();
        user.fullName = fullName;
        user.role = role;
        user.status = UserStatus.ACTIVE;
        user.lastLoginAt = now;
        return user;
    }

    /** Every sign-in refreshes the profile from the identity provider. */
    public void recordLogin(String email, String fullName, Instant now) {
        if (email != null && !email.isBlank()) {
            this.email = email.toLowerCase();
        }
        if (fullName != null && !fullName.isBlank()) {
            this.fullName = fullName;
        }
        this.lastLoginAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public String getExternalSubject() {
        return externalSubject;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
