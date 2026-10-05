package com.smd.userservice.auth;

/** The external token isn't valid (signature, issuer, audience, expiry, unverified email). → 401 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
