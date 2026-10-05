package com.smd.cartservice.api;

import com.smd.cartservice.cart.CartOwner;
import com.smd.cartservice.cart.CartService;
import com.smd.cartservice.cart.CartView;
import com.smd.cartservice.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The cart (CLAUDE.md 6.12). A signed-in CUSTOMER always gets their own single cart (the JWT decides; {@code X-Cart-Id}
 * is ignored except for a merge). A guest names their cart with {@code X-Cart-Id}. Admins don't shop: 403.
 *
 * <p><b>Why a header and not a cookie:</b> a guest cart id is a bearer secret. As a cookie the browser would send it
 * automatically with every request (cross-site requests included: CSRF exposure) and to every path. As a header,
 * only the storefront's own code sends it, and only to the cart API. (State-changing calls still need
 * {@code X-Requested-With} at nginx, like every other one.)
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    static final String CART_ID_HEADER = "X-Cart-Id";

    private final CartService carts;
    private final Caller caller;

    public CartController(CartService carts, Caller caller) {
        this.carts = carts;
        this.caller = caller;
    }

    @GetMapping
    public CartView get(@RequestHeader(value = CART_ID_HEADER, required = false) String cartId) {
        return carts.view(owner(cartId));
    }

    /** Guests: a new cart, its id in the body and in {@code X-Cart-Id}. Customers: their own cart. */
    @PostMapping
    public ResponseEntity<CartView> create() {
        refuseAdmins();
        if (caller.customerId().isPresent()) {
            return ResponseEntity.ok(carts.view(CartOwner.customer(caller.customerId().get())));
        }
        CartView cart = carts.createGuestCart();
        return ResponseEntity.status(HttpStatus.CREATED).header(CART_ID_HEADER, cart.cartId()).body(cart);
    }

    @PostMapping("/items")
    public CartView addItem(@RequestHeader(value = CART_ID_HEADER, required = false) String cartId,
                            @Valid @RequestBody AddItemRequest request) {
        return carts.addItem(owner(cartId), request.productId(), request.quantity());
    }

    /** Sets the quantity; 0 removes the line. */
    @PutMapping("/items/{productId}")
    public CartView updateItem(@RequestHeader(value = CART_ID_HEADER, required = false) String cartId,
                               @PathVariable UUID productId, @Valid @RequestBody UpdateItemRequest request) {
        return carts.updateItem(owner(cartId), productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    public CartView removeItem(@RequestHeader(value = CART_ID_HEADER, required = false) String cartId,
                               @PathVariable UUID productId) {
        return carts.removeItem(owner(cartId), productId);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@RequestHeader(value = CART_ID_HEADER, required = false) String cartId) {
        carts.clear(owner(cartId));
        return ResponseEntity.noContent().build();
    }

    /**
     * Right after sign-in: the guest cart named in {@code X-Cart-Id} is merged into the customer's cart and deleted.
     * Safe to call twice.
     */
    @PostMapping("/merge")
    public CartView merge(@RequestHeader(CART_ID_HEADER) String guestCartId) {
        refuseAdmins();
        UUID customerId = caller.customerId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to merge your cart"));
        return carts.merge(customerId, guestCartId);
    }

    private CartOwner owner(String cartId) {
        refuseAdmins();
        return caller.customerId().map(CartOwner::customer).orElseGet(() -> CartOwner.guest(cartId));
    }

    private void refuseAdmins() {
        if (caller.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admins don't have a cart");
        }
    }

    public record AddItemRequest(@NotNull UUID productId, @Min(1) @Max(10) int quantity) {
    }

    public record UpdateItemRequest(@Min(0) @Max(10) int quantity) {
    }
}
