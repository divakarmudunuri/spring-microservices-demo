package com.smd.userservice.auth;

/** A valid token, but this person may not sign in (suspended, not an admin, unknown dev user). → 403 */
public class AccessRefusedException extends RuntimeException {

    public AccessRefusedException(String message) {
        super(message);
    }
}
