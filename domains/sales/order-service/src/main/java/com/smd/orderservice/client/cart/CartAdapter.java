package com.smd.orderservice.client.cart;

import com.smd.orderservice.checkout.OrderLine;
import com.smd.orderservice.client.DownstreamErrors;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

/** The only way order-service talks to cart-service. Retry( CircuitBreaker( Bulkhead( call ))). */
@Component
public class CartAdapter {

    static final String RESILIENCE = "cartService";

    private final CartClient client;

    public CartAdapter(CartClient client) {
        this.client = client;
    }

    /**
     * The caller's cart (the relayed JWT says whose). No fallback: without the cart there is nothing to check out.
     *
     * @throws com.smd.orderservice.client.DependencyUnavailableException cart-service couldn't answer
     */
    @Retry(name = RESILIENCE, fallbackMethod = "translateFailure")
    @CircuitBreaker(name = RESILIENCE)
    @Bulkhead(name = RESILIENCE)
    public Cart getMyCart() {
        CartDto cart = client.getMyCart();
        return new Cart(cart.cartId(), cart.version(),
                cart.lines().stream().map(l -> new OrderLine(l.productId(), l.quantity())).toList());
    }

    private Cart translateFailure(Throwable failure) {
        throw DownstreamErrors.translate("cart-service", failure);
    }
}
