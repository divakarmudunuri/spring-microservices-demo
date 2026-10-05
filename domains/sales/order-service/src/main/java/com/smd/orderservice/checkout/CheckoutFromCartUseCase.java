package com.smd.orderservice.checkout;

import com.smd.orderservice.client.cart.Cart;
import com.smd.orderservice.client.cart.CartAdapter;
import com.smd.orderservice.order.Order;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * {@code POST /api/orders/checkout} (CLAUDE.md 6.12): the storefront's checkout. Reads the customer's cart from
 * cart-service (relaying their JWT), then runs <b>exactly</b> the same flow as {@link PlaceOrderUseCase}, with the
 * cart's lines as items and its id stored on the order.
 *
 * <p>The cart is not touched here. cart-service empties it when it sees the committed ORDER_CONFIRMED (which carries
 * the cart id), so a failed checkout leaves the cart as it was.
 */
@Service
public class CheckoutFromCartUseCase {

    private final CartAdapter carts;
    private final PlaceOrderUseCase placeOrder;

    public CheckoutFromCartUseCase(CartAdapter carts, PlaceOrderUseCase placeOrder) {
        this.carts = carts;
        this.placeOrder = placeOrder;
    }

    /**
     * @param idempotencyKey the client's key, or null: then it is derived from the cart's id and version, so a
     *                       double-click can't create two orders from the same cart contents
     */
    public Order checkout(UUID userId, String idempotencyKey) {
        Cart cart = carts.getMyCart();
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey : derivedKey(cart);
        return placeOrder.placeOrder(userId, key, cart.cartId(), cart.lines());
    }

    static String derivedKey(Cart cart) {
        return "cart:" + cart.cartId() + ":v" + cart.version();
    }
}
