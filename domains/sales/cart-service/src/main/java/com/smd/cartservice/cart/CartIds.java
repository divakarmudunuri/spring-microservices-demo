package com.smd.cartservice.cart;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class CartIds {

    private CartIds() {
    }

    /**
     * A guest cart id is a random UUID v4: unguessable, it is the guest's only proof of ownership.
     */
    static String newGuestCartId() {
        return UUID.randomUUID().toString();
    }

    /**
     * A customer's single cart has an id derived from the user id, so two concurrent "create my cart" calls write the
     * same item and the conditional put lets only one win. Being guessable is harmless: a customer cart is only ever
     * reached through the customer's JWT, never by its id.
     */
    static String customerCartId(UUID userId) {
        return UUID.nameUUIDFromBytes(("customer-cart:" + userId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Never log a guest cart id in full: it is a bearer secret. */
    static String mask(String cartId) {
        return cartId == null || cartId.length() < 8 ? "****" : cartId.substring(0, 8) + "…";
    }
}
