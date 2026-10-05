package com.smd.userservice.user;

import java.util.UUID;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID id) {
        super("User " + id + " not found");
    }
}
