package com.smd.cartservice.cart;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A cart as the storefront (and order-service's checkout) sees it: the stored lines enriched with the
 * <b>current</b> name, picture, price and level from product-service. If product-service is unavailable the lines
 * come without product data and {@code degraded} is true: never a 500.
 * <p>
 * {@code createdAt} tells two lives of the same cart id apart: a customer's cart id is derived from the user id, and
 * an emptied cart is deleted, so the next one starts again at a low {@code version}. order-service puts it in the
 * idempotency key it derives for a checkout.
 */
public record CartView(String cartId, boolean guest, long version, List<Line> lines, BigDecimal subtotal,
                       String currency, boolean degraded, String createdAt) {

    public record Line(UUID productId, int quantity, String name, String slug, String imageUrl, BigDecimal unitPrice,
                       String availability, BigDecimal lineTotal) {
    }
}
