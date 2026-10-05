package com.smd.orderservice.wallet;

import java.util.UUID;

/** Unchecked on purpose: thrown inside the checkout transaction to roll all of it back. */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID userId) {
        super("Wallet balance of user " + userId + " does not cover the order");
    }
}
