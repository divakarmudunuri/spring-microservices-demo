package com.smd.cartservice.cart;

import java.util.UUID;

/**
 * Who is asking for a cart: a signed-in customer (by the JWT), or a guest holding a cart id ({@code X-Cart-Id}).
 * The guest cart id is an unguessable bearer secret (UUID v4): whoever has it may use the cart.
 */
public record CartOwner(UUID customerId, String guestCartId) {

    public static CartOwner customer(UUID id) {
        return new CartOwner(id, null);
    }

    public static CartOwner guest(String cartId) {
        return new CartOwner(null, cartId);
    }

    public boolean isCustomer() {
        return customerId != null;
    }
}
