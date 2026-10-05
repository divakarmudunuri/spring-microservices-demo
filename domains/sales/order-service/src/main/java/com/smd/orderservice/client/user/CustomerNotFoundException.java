package com.smd.orderservice.client.user;

import java.util.UUID;

public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(UUID id) {
        super("User " + id + " not found");
    }
}
