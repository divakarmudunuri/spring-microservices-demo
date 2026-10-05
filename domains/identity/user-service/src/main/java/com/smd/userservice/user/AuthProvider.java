package com.smd.userservice.user;

/** Where the identity comes from. {@code LOCAL} is only the two seeded sample users (dev-idp). */
public enum AuthProvider {
    GOOGLE,
    OKTA,
    LOCAL
}
